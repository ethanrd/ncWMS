/*
 * Copyright (c) 2009 The University of Reading
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the University of Reading, nor the names of the
 *    authors or contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package uk.ac.rdg.resc.edal.cdm;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import uk.ac.rdg.resc.edal.cdm.kdtree.Point;
import uk.ac.rdg.resc.edal.coverage.grid.GridCoordinates;
import uk.ac.rdg.resc.edal.geometry.HorizontalPosition;
import uk.ac.rdg.resc.edal.geometry.LonLatPosition;
import java.util.Map;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.dt.GridCoordSystem;
import ucar.nc2.dt.GridDatatype;
import uk.ac.rdg.resc.edal.cdm.kdtree.KDTree;
import uk.ac.rdg.resc.edal.coverage.grid.HorizontalGrid;
import uk.ac.rdg.resc.edal.coverage.grid.impl.GridCoordinatesImpl;
import uk.ac.rdg.resc.edal.coverage.grid.impl.RegularGridImpl;
import uk.ac.rdg.resc.edal.util.CollectionUtils;
import uk.ac.rdg.resc.edal.util.Utils;
import uk.ac.rdg.resc.ncwms.graphics.ColorPalette;
import uk.ac.rdg.resc.ncwms.graphics.ImageProducer;
import uk.ac.rdg.resc.ncwms.graphics.ImageProducer.Style;

/**
 * A HorizontalGrid that uses an KdTree to look up the nearest neighbour of a point.
 */
final class KdTreeGrid extends AbstractCurvilinearGrid
{
    private static final Logger logger = LoggerFactory.getLogger(KdTreeGrid.class);

    /**
     * In-memory cache of LookUpTableGrid objects to save expensive re-generation of same object
     * @todo The CurvilinearGrid objects can be very big.  Really we only need to key
     * on the arrays of lon and lat: all other quantities can be calculated from
     * these.  This means that we could make other large objects available for
     * garbage collection.
     */
    private static final Map<CurvilinearGrid, KdTreeGrid> CACHE =
            CollectionUtils.newHashMap();

    private final KDTree kdTree;
    private final float max_distance;

    /**
     * The passed-in coordSys must have 2D horizontal coordinate axes.
     */
    public static KdTreeGrid generate(GridCoordSystem coordSys)
    {
        CurvilinearGrid curvGrid = new CurvilinearGrid(coordSys);

        synchronized(CACHE)
        {
            KdTreeGrid kdTreeGrid = CACHE.get(curvGrid);
            if (kdTreeGrid == null)
            {
                logger.debug("Need to generate new kdtree");
                // Create the KdTree for this coordinate system
                long start = System.nanoTime();
                KDTree kdTree = new KDTree(curvGrid);
                kdTree.buildTree();
                long finish = System.nanoTime();
                logger.debug("Generated new kdtree in {} seconds", (finish - start) / 1.e9);
                System.out.println("Verifying tree");
                kdTree.verifyChildren(0);
                System.out.println("Tree finished verifying");
                // Create the RTreeGrid
                kdTreeGrid = new KdTreeGrid(curvGrid, kdTree);
                // Now put this in the cache
                CACHE.put(curvGrid, kdTreeGrid);
            }
            else
            {
                logger.debug("kdree found in cache");
            }
            return kdTreeGrid;
        }
    }

    /** Private constructor to prevent direct instantiation */
    private KdTreeGrid(CurvilinearGrid curvGrid, KDTree kdTree)
    {
        // All points will be returned in WGS84 lon-lat
        super(curvGrid);
        this.kdTree = kdTree;
        this.max_distance = (float)Math.sqrt(curvGrid.getMeanCellArea());
    }

    /**
     * @return the nearest grid point to the given lat-lon point, or null if the
     * lat-lon point is not contained within this layer's domain. The grid point
     * is given as a two-dimensional integer array: [i,j].
     */
    @Override
    public GridCoordinates findNearestGridPoint(HorizontalPosition pos)
    {
        LonLatPosition lonLatPos = Utils.transformToWgs84LonLat(pos);
        double lon = lonLatPos.getLongitude();
        double lat = lonLatPos.getLatitude();

        List<Point> nns = this.kdTree.approxNearestNeighbour(lat, lon, 0.5);
        for (Point nn : nns) {
            int i = nn.index % this.curvGrid.getNi();
            int j = nn.index / this.curvGrid.getNi();
            CurvilinearGrid.Cell cell = this.curvGrid.getCell(i, j);
            if (cell.contains(lonLatPos)) {
                return new GridCoordinatesImpl(cell.getI(), cell.getJ());
            }
            // Do a neighbour search, as sometimes the nearest neighbour does
            // not fall precisely into the cell
            for (CurvilinearGrid.Cell neighbour : cell.getEdgeNeighbours()) {
                if (neighbour.contains(lonLatPos)) {
                    return new GridCoordinatesImpl(neighbour.getI(), neighbour.getJ());
                }
            }
            for (CurvilinearGrid.Cell neighbour : cell.getCornerNeighbours()) {
                if (neighbour.contains(lonLatPos)) {
                    return new GridCoordinatesImpl(neighbour.getI(), neighbour.getJ());
                }
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception
    {
        int size = 256;
        NetcdfDataset nc = NetcdfDataset.openDataset("C:\\Godiva2_data\\UCA25D\\UCA25D.20101118.04.nc");
        GridDatatype grid = CdmUtils.getGridDatatype(nc, "sea_level");
        KdTreeGrid kdTreeGrid = KdTreeGrid.generate(grid.getCoordinateSystem());
        System.out.println("Generated kdTreeGrid");
        HorizontalGrid targetDomain = new RegularGridImpl(kdTreeGrid.getExtent(), size, size);
        long start = System.nanoTime();
        List<Float> data = CdmUtils.readHorizontalPoints(nc, grid, kdTreeGrid, 0, 0, targetDomain);
        long finish = System.nanoTime();
        System.out.printf("Produced data for image in %f seconds\n", (finish - start) / 1.e9);
        kdTreeGrid.kdTree.printApproxQueryStats();
        nc.close();
        ImageProducer ip = new ImageProducer.Builder()
            .palette(ColorPalette.get(null))
            .style(Style.BOXFILL)
            .height(size)
            .width(size)
            .build();
        ip.addFrame(data, null);
        List<BufferedImage> ims = ip.getRenderedFrames();
        ImageIO.write(ims.get(0), "png", new File("C:\\test.png"));
    }
}
