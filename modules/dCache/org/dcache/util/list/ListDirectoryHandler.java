package org.dcache.util.list;

import java.util.UUID;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.io.Serializable;
import java.io.File;
import java.io.IOException;

import diskCacheV111.util.CacheException;
import diskCacheV111.util.FileNotFoundCacheException;
import diskCacheV111.util.PnfsHandler;
import diskCacheV111.util.PnfsId;
import org.dcache.vehicles.PnfsListDirectoryMessage;
import org.dcache.vehicles.FileAttributes;
import org.dcache.namespace.FileAttribute;
import org.dcache.cells.CellMessageReceiver;
import org.dcache.util.Glob;
import org.dcache.util.Interval;
import org.dcache.util.CacheExceptionFactory;

import dmg.util.CollectionFactory;
import dmg.cells.nucleus.NoRouteToCellException;

import org.apache.log4j.Logger;

/**
 * DirectoryListSource which delegates the list operation to the
 * PnfsManager.
 *
 * Large directories are broken into several reply messages by the
 * PnfsManager. For that reason the regular Cells callback mechanism
 * for replies cannot be used. Instead messages of type
 * PnfsListDirectoryMessage must be routed to the
 * ListDirectoryHandler. This also has the consequence that a
 * ListDirectoryHandler cannot be used from the Cells messages
 * thread. Any attempt to do so will cause the message thread to
 * block, as the replies cannot be delivered to the
 * ListDirectoryHandler.
 */
public class ListDirectoryHandler
    implements CellMessageReceiver, DirectoryListSource
{
    private final static Logger _log =
        Logger.getLogger(ListDirectoryHandler.class);
    private final static long DEFAULT_TIMEOUT = 60000;

    private final PnfsHandler _pnfs;
    private final Map<UUID,Stream> _replies =
        CollectionFactory.newConcurrentHashMap();
    private volatile long _timeout = DEFAULT_TIMEOUT;

    public ListDirectoryHandler(PnfsHandler pnfs)
    {
        _pnfs = pnfs;
    }

    /**
     * Sets the timeout for two consecutive reply messages in a reply
     * stream.
     */
    public void setTimeout(long timeout)
    {
        _timeout = timeout;
    }

    public long getTimeout()
    {
        return _timeout;
    }

    /**
     * Sends a directory list request to PnfsManager. The result is
     * provided as a stream of directory entries.
     *
     * The method blocks until the first set of directory entries have
     * been received from the server.  Hence errors like
     * FILE_NOT_FOUND are thrown by the call to the list method rather
     * than while iterating over the stream.
     */
    @Override
    public DirectoryStream
        list(File path, Glob pattern, Interval range)
        throws InterruptedException, CacheException
    {
        return list(path, pattern, range, EnumSet.noneOf(FileAttribute.class));
    }

    /**
     * Sends a directory list request to PnfsManager. The result is
     * provided as a stream of directory entries.
     *
     * The method blocks until the first set of directory entries have
     * been received from the server.  Hence errors like
     * FILE_NOT_FOUND are thrown by the call to the list method rather
     * than while iterating over the stream.
     */
    @Override
    public DirectoryStream
        list(File path, Glob pattern, Interval range,
             Set<FileAttribute> attributes)
        throws InterruptedException, CacheException
    {
        String dir = path.toString();
        PnfsListDirectoryMessage msg =
            new PnfsListDirectoryMessage(dir, pattern, range, attributes);
        UUID uuid = msg.getUUID();
        boolean success = false;
        Stream stream = new Stream(dir, uuid);
        try {
            _replies.put(uuid, stream);
            _pnfs.send(msg);
            stream.waitForMoreEntries();
            success = true;
            return stream;
        } finally {
            if (!success) {
                _replies.remove(uuid);
            }
        }
    }

    @Override
    public void printFile(DirectoryListPrinter printer, File path)
        throws InterruptedException, CacheException
    {
        Set<FileAttribute> required =
            printer.getRequiredAttributes();
        PnfsId id =
            _pnfs.getPnfsIdByPath(path.toString());
        FileAttributes attributes =
            _pnfs.getFileAttributes(id, required);
        FileAttributes dirAttr =
            _pnfs.getFileAttributes(path.getParent(), required);
        printer.print(dirAttr,
                      new DirectoryEntry(path.getName(), id, attributes));
    }

    @Override
    public int printDirectory(DirectoryListPrinter printer, File path,
                              Glob glob, Interval range)
        throws InterruptedException, CacheException
    {
        Set<org.dcache.namespace.FileAttribute> required =
            printer.getRequiredAttributes();
        FileAttributes dirAttr =
            _pnfs.getFileAttributes(path.toString(), required);
        DirectoryStream stream =
            list(path, glob, range, required);
        try {
            int total = 0;
            for (DirectoryEntry entry: stream) {
                printer.print(dirAttr, entry);
                total++;
            }
            return total;
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                throw new CacheException(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                         "Failed to close directory stream (" +
                                         e.getMessage() + ")");
            }
        }
    }

    /**
     * Callback for delivery of replies from
     * PnfsManager. PnfsListDirectoryMessage have to be routed to this
     * message.
     */
    public void messageArrived(PnfsListDirectoryMessage reply)
    {
        if (reply.isReply()) {
            try {
                UUID uuid = reply.getUUID();
                Stream stream;
                if (reply.isFinal()) {
                    stream = _replies.remove(uuid);
                } else {
                    stream = _replies.get(uuid);
                }
                if (stream != null) {
                    stream.put(reply);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Implementation of DirectoryStream, translating
     * PnfsListDirectoryMessage replies to a stream of
     * DirectoryEntries.
     *
     * The stream acts as its own iterator, and multiple iterators are
     * not supported.
     */
    public class Stream
        implements DirectoryStream, Iterator<DirectoryEntry>
    {
        private final BlockingQueue<PnfsListDirectoryMessage> _queue =
            CollectionFactory.newLinkedBlockingQueue();
        private final UUID _uuid;
        private final String _path;
        private boolean _isFinal = false;
        private Iterator<DirectoryEntry> _iterator;

        public Stream(String path, UUID uuid)
        {
            _path = path;
            _uuid = uuid;
        }

        @Override
        public void close()
        {
            _replies.remove(_uuid);
        }

        private void put(PnfsListDirectoryMessage msg)
            throws InterruptedException
        {
            _queue.put(msg);
        }

        private void waitForMoreEntries()
            throws InterruptedException, CacheException
        {
            if (_isFinal) {
                _iterator = null;
                return;
            }

            PnfsListDirectoryMessage msg =
                _queue.poll(_timeout, TimeUnit.MILLISECONDS);
            if (msg == null) {
                throw new CacheException(CacheException.TIMEOUT,
                                         "Timeout during directory list");
            }

            _isFinal = msg.isFinal();

            if (msg.getReturnCode() != 0) {
                throw CacheExceptionFactory.exceptionOf(msg);
            }

            _iterator = msg.getEntries().iterator();

            /* If the message is empty, then the iterator has no next
             * element. In that case we wait for the next reply. This
             * may in particular happen with the final message.
             */
            if (!_iterator.hasNext()) {
                waitForMoreEntries();
            }
        }

        @Override
        public Iterator<DirectoryEntry> iterator()
        {
            return this;
        }

        @Override
        public boolean hasNext()
        {
            try {
                if (_iterator == null || !_iterator.hasNext()) {
                    waitForMoreEntries();
                    if (_iterator == null) {
                        return false;
                    }
                }
            } catch (CacheException e) {
                _log.error("Listing of " + _path + " incomplete: " +
                           e.getMessage());
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            return true;
        }

        @Override
        public DirectoryEntry next()
        {
            if (!hasNext())
                throw new NoSuchElementException();

            return _iterator.next();
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }
}