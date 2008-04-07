package org.dcache.poolmanager;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import diskCacheV111.poolManager.CostModule;
import diskCacheV111.poolManager.PoolSelectionUnit;
import diskCacheV111.pools.PoolCostInfo;

public class Utils {

    /*
     * no instance allowed
     */

    private Utils() {
        // static methods only
    }



    public static Map<String, Long> linkGroupSize(PoolSelectionUnit selectionUnit, CostModule costModule ) {

        String [] linkGroups = selectionUnit.getLinkGroups();
        //PoolLinkGroupInfo[] linkGroupInfo = new PoolLinkGroupInfo[linkGroups.length];
        Map<String, Long> linkGroupSize = new HashMap<String, Long>(linkGroups.length);

        /*
         * get list of all defined link groups
         * for each link group get list of links
         * for each link in the group find all active pools and
         * calculate available space ( total - removable )
         */


        for( int i_goup = 0; i_goup < linkGroups.length; i_goup++ ) {

                String[] links = selectionUnit.getLinksByGroupName(linkGroups[i_goup]);
                long linkAvailableSpace = 0;
                long linkTotalSpace = 0;
                for(int i_link = 0; i_link < links.length; i_link++ ) {

                    PoolSelectionUnit.SelectionLink selectionLink = selectionUnit.getLinkByName(links[i_link]);
                    Iterator<PoolSelectionUnit.SelectionPool> poolsIterator = selectionLink.pools();
                    while( poolsIterator.hasNext() ) {
                        PoolSelectionUnit.SelectionPool pool = poolsIterator.next();
                        if ( pool.isEnabled() ) {
                            String poolName = pool.getName();
                            PoolCostInfo poolCostInfo = costModule.getPoolCostInfo(poolName);
                            if(poolCostInfo != null) {
                                linkAvailableSpace += poolCostInfo.getSpaceInfo().getFreeSpace() + poolCostInfo.getSpaceInfo().getRemovableSpace();
                                linkTotalSpace += poolCostInfo.getSpaceInfo().getTotalSpace();
                            }
                        }
                    }
                }

                linkGroupSize.put(linkGroups[i_goup], Long.valueOf(linkAvailableSpace));
        }

        return linkGroupSize;
    }

}
