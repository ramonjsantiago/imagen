/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.eclipse.imagen.media.mlib;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.Map;

import org.eclipse.imagen.BorderExtender;
import org.eclipse.imagen.ImageLayout;
import org.eclipse.imagen.Interpolation;
import org.eclipse.imagen.WarpGrid;
import org.eclipse.imagen.WarpOpImage;
import org.eclipse.imagen.media.util.ImageUtil;

import com.sun.medialib.mlib.Constants;
import com.sun.medialib.mlib.Image;
import com.sun.medialib.mlib.mediaLibImage;

/**
 * An <code>OpImage</code> implementing the Grid "Warp" operation
 * using mediaLib.
 *
 * <p> With warp operations, there is no forward mapping (from source to
 * destination).  JAI images are tiled, while mediaLib does not handle
 * tiles and consider each tile an individual image.  For each tile in
 * destination, in order not to cobble the entire source image, the
 * <code>computeTile</code> method in this class attemps to do a backward
 * mapping on the tile region using the pixels along the perimeter of the
 * rectangular region.  The hope is that the mapped source rectangle
 * should include all source pixels needed for this particular destination
 * tile.  However, with certain unusual warp points, an inner destination
 * pixel may be mapped outside of the mapped perimeter pixels.  In this
 * case, this destination pixel is not filled, and left black.
 *
 * @see org.eclipse.imagen.operator.WarpDescriptor
 * @see MlibWarpRIF
 *
 * @since 1.0
 *
 */
final class MlibWarpGridOpImage extends WarpOpImage {

    /** X grid settings. */
    private int xStart;
    private int xStep;
    private int xNumCells;
    private int xEnd;

    /** Y grid settings. */
    private int yStart;
    private int yStep;
    private int yNumCells;
    private int yEnd;

    /** Grid points. */
    private float[] xWarpPos;
    private float[] yWarpPos;

    /**
     * Indicates what kind of interpolation to use; may be
     * <code>Constants.MLIB_NEAREST</code>,
     * <code>Constants.MLIB_BILINEAR</code>,
     * or <code>Constants.MLIB_BICUBIC</code>,
     * and was determined in <code>MlibWarpRIF.create()</code>.
     */
    private int filter;


    /**
     * Returns the minimum bounding box of the region of the specified
     * source to which a particular <code>Rectangle</code> of the
     * destination will be mapped.
     *
     * @param destRect the <code>Rectangle</code> in destination coordinates.
     * @param sourceIndex the index of the source image.
     *
     * @return a <code>Rectangle</code> indicating the source bounding box,
     *         or <code>null</code> if the bounding box is unknown.
     *
     * @throws IllegalArgumentException if <code>sourceIndex</code> is
     *         negative or greater than the index of the last source.
     * @throws IllegalArgumentException if <code>destRect</code> is
     *         <code>null</code>.
     */
    protected Rectangle backwardMapRect(Rectangle destRect,
                                        int sourceIndex) {
        // Superclass method will throw documented exceptions if needed.
        Rectangle wrect = super.backwardMapRect(destRect, sourceIndex);

        // "Dilate" the backwarp mapped rectangle to account for
        // the lack of being able to know the floating point result of
        // mapDestRect() and to mimic what is done in AffineOpImage.
        // See bug 4518223 for more information.
        wrect.setBounds(wrect.x - 1, wrect.y - 1,
                        wrect.width + 2, wrect.height + 2);

        return wrect;
    }

    /**
     * Constructs a <code>MlibWarpGridOpImage</code>.
     *
     * @param source  The source image.
     * @param layout  The destination image layout.
     * @param warp    An object defining the warp algorithm.
     * @param interp  An object describing the interpolation method.
     */
    public MlibWarpGridOpImage(RenderedImage source,
                               BorderExtender extender,
                               Map config,
                               ImageLayout layout,
                               WarpGrid warp,
                               Interpolation interp,
                               int filter,
                               double[] backgroundValues) {
        super(source,
              layout,
              config,
              true,
              extender,
              interp,
              warp,
              backgroundValues);

        this.filter = filter;	// interpolation

        xStart = warp.getXStart();
        xStep = warp.getXStep();
        xNumCells = warp.getXNumCells();
        xEnd = xStart + xStep * xNumCells;

        yStart = warp.getYStart();
        yStep = warp.getYStep();
        yNumCells = warp.getYNumCells();
        yEnd = yStart + yStep * yNumCells;

        xWarpPos = warp.getXWarpPos();
        yWarpPos = warp.getYWarpPos();
    }

    /**
     * Computes a tile.  A new <code>WritableRaster</code> is created to
     * represent the requested tile.  Its width and height equals to this
     * image's tile width and tile height respectively.  If the requested
     * tile lies outside of the image's boundary, the created raster is
     * returned with all of its pixels set to 0.
     *
     * <p> This method overrides the method in <code>WarpOpImage</code>
     * and performs source cobbling when necessary.  MediaLib is used to
     * calculate the actual warping.
     *
     * @param tileX The X index of the tile.
     * @param tileY The Y index of the tile.
     *
     * @return The tile as a <code>Raster</code>.
     */
    public Raster computeTile(int tileX, int tileY) {
        /* The origin of the tile. */
        Point org = new Point(tileXToX(tileX), tileYToY(tileY));

        /* Create a new WritableRaster to represent this tile. */
        WritableRaster dest = createWritableRaster(sampleModel, org);

        /* Find the intersection between this tile and the writable bounds. */
        Rectangle rect0 = new Rectangle(org.x, org.y, tileWidth, tileHeight);
        Rectangle destRect = rect0.intersection(computableBounds);
        Rectangle destRect1 = rect0.intersection(getBounds());

        if (destRect.isEmpty()) {
	    if (setBackground) {
		ImageUtil.fillBackground(dest, destRect1, backgroundValues);
	    }
            return dest;	// tile completely outside of writable bounds
        }

        if (!destRect1.equals(destRect)) {
            // beware that destRect1 contains destRect
            ImageUtil.fillBordersWithBackgroundValues(destRect1, destRect, dest, backgroundValues);
        }

        Raster[] sources = new Raster[1];
        Rectangle srcBounds = getSourceImage(0).getBounds();

        int x0 = destRect.x;			// first x point
        int x1 = x0 + destRect.width - 1;	// last x point
        int y0 = destRect.y;			// first y point
        int y1 = y0 + destRect.height - 1;	// last y point

        if (x0 >= xEnd || x1 < xStart || y0 >= yEnd || y1 < yStart) {
            /* Tile is completely outside of warp grid; do copy. */
            Rectangle rect = srcBounds.intersection(destRect);

            if (!rect.isEmpty()) {
                sources[0] = getSourceImage(0).getData(rect);
                copyRect(sources, dest, rect);

                // Recycle the source tile
                if(getSourceImage(0).overlapsMultipleTiles(rect)) {
                    recycleTile(sources[0]);
                }
            }

            return dest;
        }

        if (x0 < xStart) {	// region left of warp grid
            Rectangle rect = srcBounds.intersection(new Rectangle(x0, y0,
                                       xStart - x0, y1 - y0 + 1));

            if (!rect.isEmpty()) {
                sources[0] = getSourceImage(0).getData(rect);
                copyRect(sources, dest, rect);

                // Recycle the source tile
                if(getSourceImage(0).overlapsMultipleTiles(rect)) {
                    recycleTile(sources[0]);
                }
            }

            x0 = xStart;
        }

        if (x1 >= xEnd) {	// region right of warp grid
            Rectangle rect = srcBounds.intersection(new Rectangle(xEnd, y0,
                                       x1 - xEnd + 1, y1 - y0 + 1));

            if (!rect.isEmpty()) {
                sources[0] = getSourceImage(0).getData(rect);
                copyRect(sources, dest, rect);

                // Recycle the source tile
                if(getSourceImage(0).overlapsMultipleTiles(rect)) {
                    recycleTile(sources[0]);
                }
            }

            x1 = xEnd - 1;
        }

        if (y0 < yStart) {	// region above warp grid
            Rectangle rect = srcBounds.intersection(new Rectangle(x0, y0,
                                       x1 - x0 + 1, yStart - y0));

            if (!rect.isEmpty()) {
                sources[0] = getSourceImage(0).getData(rect);
                copyRect(sources, dest, rect);

                // Recycle the source tile
                if(getSourceImage(0).overlapsMultipleTiles(rect)) {
                    recycleTile(sources[0]);
                }
            }

            y0 = yStart;
        }

        if (y1 >= yEnd) {	// region below warp grid
            Rectangle rect = srcBounds.intersection(new Rectangle(x0, yEnd,
                                       x1 - x0 + 1, y1 - yEnd + 1));

            if (!rect.isEmpty()) {
                sources[0] = getSourceImage(0).getData(rect);
                copyRect(sources, dest, rect);

                // Recycle the source tile
                if(getSourceImage(0).overlapsMultipleTiles(rect)) {
                    recycleTile(sources[0]);
                }
            }

            y1 = yEnd -1;
        }

        /* The region within the warp grid. */
        destRect = new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);

        /* Map destination rectangle to source space. */
        Rectangle srcRect = backwardMapRect(destRect, 0).intersection(srcBounds);

        if (!srcRect.isEmpty()) {
            /* Add the interpolation paddings. */
            int l = interp == null ? 0 : interp.getLeftPadding();
            int r = interp == null ? 0 : interp.getRightPadding();
            int t = interp == null ? 0 : interp.getTopPadding();
            int b = interp == null ? 0 : interp.getBottomPadding();

            srcRect = new Rectangle(srcRect.x - l,
                                    srcRect.y - t,
                                    srcRect.width + l + r,
                                    srcRect.height + t + b);

            sources[0] = getBorderExtender() != null ?
                         getSourceImage(0).getExtendedData(srcRect, extender) :
                         getSourceImage(0).getData(srcRect);

            computeRect(sources, dest, destRect);

            // Recycle the source tile
            if(getSourceImage(0).overlapsMultipleTiles(srcRect)) {
                recycleTile(sources[0]);
            }
        }

        return dest;
    }

    /**
     * Performs the "grid warp" operation on a rectangular region of
     * the image.
     */
    protected void computeRect(Raster[] sources,
                               WritableRaster dest,
                               Rectangle destRect) {
        int formatTag = MediaLibAccessor.findCompatibleTag(sources, dest);

        Raster source = sources[0];

        MediaLibAccessor srcMA =
            new MediaLibAccessor(source, source.getBounds(), formatTag);
        MediaLibAccessor dstMA =
            new MediaLibAccessor(dest, destRect, formatTag);

        mediaLibImage[] srcMLI = srcMA.getMediaLibImages();
        mediaLibImage[] dstMLI = dstMA.getMediaLibImages();

        switch (dstMA.getDataType()) {
        case DataBuffer.TYPE_BYTE:
        case DataBuffer.TYPE_USHORT:
        case DataBuffer.TYPE_SHORT:
        case DataBuffer.TYPE_INT:
            if (setBackground)
                for (int i = 0 ; i < dstMLI.length; i++) {
                    Image.GridWarp2(dstMLI[i], srcMLI[i],
                                    xWarpPos, yWarpPos,
                                    source.getMinX(),
                                    source.getMinY(),
                                    xStart - destRect.x,
                                    xStep, xNumCells,
                                    yStart - destRect.y,
                                    yStep, yNumCells,
                                    filter,
                                    Constants.MLIB_EDGE_DST_NO_WRITE,
                                    intBackgroundValues);
                }
            else
                for (int i = 0 ; i < dstMLI.length; i++) {
                    Image.GridWarp(dstMLI[i], srcMLI[i],
                                   xWarpPos, yWarpPos,
                                   source.getMinX(),
                                   source.getMinY(),
                                   xStart - destRect.x,
                                   xStep, xNumCells,
                                   yStart - destRect.y,
                                   yStep, yNumCells,
                                   filter,
                                   Constants.MLIB_EDGE_DST_NO_WRITE);
                    MlibUtils.clampImage(dstMLI[i], getColorModel());
                }
            break;

        case DataBuffer.TYPE_FLOAT:
        case DataBuffer.TYPE_DOUBLE:
            if (setBackground)
                for (int i = 0 ; i < dstMLI.length; i++) {
                    Image.GridWarp2_Fp(dstMLI[i], srcMLI[i],
                                       xWarpPos, yWarpPos,
                                       source.getMinX(),
                                       source.getMinY(),
                                       xStart - destRect.x,
                                       xStep, xNumCells,
                                       yStart - destRect.y,
                                       yStep, yNumCells,
                                       filter,
                                       Constants.MLIB_EDGE_DST_NO_WRITE,
                                       backgroundValues);
                }
            else
                for (int i = 0 ; i < dstMLI.length; i++) {
                    Image.GridWarp_Fp(dstMLI[i], srcMLI[i],
                                      xWarpPos, yWarpPos,
                                      source.getMinX(),
                                      source.getMinY(),
                                      xStart - destRect.x,
                                      xStep, xNumCells,
                                      yStart - destRect.y,
                                      yStep, yNumCells,
                                      filter,
                                      Constants.MLIB_EDGE_DST_NO_WRITE);
                }
            break;

        default:
            throw new RuntimeException(JaiI18N.getString("Generic2"));
        }

        if (dstMA.isDataCopy()) {
            dstMA.clampDataArrays();
            dstMA.copyDataToRaster();
        }
    }

    /**
     * Copies the pixels of a rectangle from source <code>Raster</code>
     * to destination <code>Raster</code> using mediaLib.  This method
     * is used to copy pixels outside of the warp grid.
     */
    private void copyRect(Raster[] sources,
                          WritableRaster dest,
                          Rectangle destRect) {
        int formatTag = MediaLibAccessor.findCompatibleTag(sources, dest);

        MediaLibAccessor srcMA =
            new MediaLibAccessor(sources[0], destRect, formatTag);
        MediaLibAccessor dstMA =
            new MediaLibAccessor(dest, destRect, formatTag);

        mediaLibImage[] srcMLI = srcMA.getMediaLibImages();
        mediaLibImage[] dstMLI = dstMA.getMediaLibImages();

        for (int i = 0 ; i < dstMLI.length; i++) {
            Image.Copy(dstMLI[i], srcMLI[i]);
        }

        if (dstMA.isDataCopy()) {
            dstMA.copyDataToRaster();
        }
    }
}
