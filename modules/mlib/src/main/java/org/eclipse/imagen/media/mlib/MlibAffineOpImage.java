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
import java.awt.image.SampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.awt.image.renderable.ParameterBlock;
import java.awt.image.renderable.RenderedImageFactory;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import org.eclipse.imagen.BorderExtender;
import org.eclipse.imagen.GeometricOpImage;
import org.eclipse.imagen.ImageLayout;
import org.eclipse.imagen.Interpolation;
import org.eclipse.imagen.InterpolationNearest;
import org.eclipse.imagen.InterpolationBilinear;
import org.eclipse.imagen.InterpolationBicubic;
import org.eclipse.imagen.InterpolationBicubic2;
import org.eclipse.imagen.KernelJAI;
import org.eclipse.imagen.OpImage;
import org.eclipse.imagen.PlanarImage;
import org.eclipse.imagen.util.ImagingException;
import org.eclipse.imagen.util.ImagingListener;
import java.util.Map;
import com.sun.medialib.mlib.*;
import org.eclipse.imagen.media.util.ImageUtil;
// import org.eclipse.imagen.media.test.OpImageTester;

/**
 * An OpImage class to perform AffineTransform on a source image.
 *
 */
class MlibAffineOpImage extends GeometricOpImage {

    /**
     * The transformation in matrix form (medialib expects this form)
     */
    protected double f_transform[];
    protected double m_transform[];
    protected double medialib_tr[];
    protected AffineTransform transform;
    protected AffineTransform i_transform;

    /** The Interpolation object. */
    protected Interpolation interp;

    /** Store source & padded rectangle info */
    private Rectangle srcimg, padimg;

    /** The BorderExtender */
    protected BorderExtender extender;

    /** The true writable area */
    private Rectangle theDest;

    /** Cache the ImagingListener */
    private ImagingListener listener;

    /**
     * Padding values for interpolation
     */
    public int lpad, rpad, tpad, bpad;

    private static ImageLayout layoutHelper(ImageLayout layout,
                                            RenderedImage source,
                                            AffineTransform forward_tr) {
        ImageLayout newLayout;
        if (layout != null) {
            newLayout = (ImageLayout)layout.clone();
        } else {
            newLayout = new ImageLayout();
        }

        //
        // Get sx0,sy0 coordinates & width & height of the source.
        //
        float sx0 = (float)source.getMinX();
        float sy0 = (float)source.getMinY();
        float sw = (float)source.getWidth();
        float sh = (float)source.getHeight();

        //
        // The 4 points (clockwise order) are
        //      (sx0, sy0),    (sx0+sw, sy0)
        //      (sx0, sy0+sh), (sx0+sw, sy0+sh)
        //
        Point2D[] pts = new Point2D[4];
        pts[0] = new Point2D.Float(sx0, sy0);
        pts[1] = new Point2D.Float((sx0+sw), sy0);
        pts[2] = new Point2D.Float((sx0+sw), (sy0+sh));
        pts[3] = new Point2D.Float(sx0, (sy0+sh));

        // Forward map
        forward_tr.transform(pts, 0, pts, 0, 4);

        float dx0 = Float.MAX_VALUE;
        float dy0 = Float.MAX_VALUE;
        float dx1 = -Float.MAX_VALUE;
        float dy1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float px = (float)pts[i].getX();
            float py = (float)pts[i].getY();

            dx0 = Math.min(dx0, px);
            dy0 = Math.min(dy0, py);
            dx1 = Math.max(dx1, px);
            dy1 = Math.max(dy1, py);
        }

        //
        // Get the width & height of the resulting bounding box.
        // This is set on the layout
        //
        int lw = (int)(dx1 - dx0);
        int lh = (int)(dy1 - dy0);

        //
        // Set the starting integral coordinate
        // with the following criterion.
        // If it's greater than 0.5, set it to the next integral value (ceil)
        // else set it to the integral value (floor).
        //
        int lx0, ly0;

        int i_dx0 = (int)Math.floor(dx0);
        if (Math.abs(dx0 - i_dx0) <= 0.5) {
            lx0 = i_dx0;
        } else {
            lx0 = (int) Math.ceil(dx0);
        }

        int i_dy0 = (int)Math.floor(dy0);
        if (Math.abs(dy0 - i_dy0) <= 0.5) {
            ly0 = i_dy0;
        } else {
            ly0 = (int) Math.ceil(dy0);
        }

        //
        // Create the layout
        //
        newLayout.setMinX(lx0);
        newLayout.setMinY(ly0);
        newLayout.setWidth(lw);
        newLayout.setHeight(lh);

        return newLayout;
    }

    /**
     * Creates a MlibAffineOpImage given a ParameterBlock containing the
     * image source and the AffineTransform and the interpolation.
     * The image dimensions are derived from the source image.  The tile
     * grid layout, SampleModel, and ColorModel may optionally be specified
     * by an ImageLayout object.
     *
     * @param source a RenderedImage.
     * @param extender a BorderExtender, or null.

     *        or null.  If null, a default cache will be used.
     * @param layout an ImageLayout optionally containing the tile grid layout,
     *        SampleModel, and ColorModel, or null.
     * @param kernel the convolution KernelJAI.
     */
    public MlibAffineOpImage(RenderedImage source,
                             ImageLayout layout,
                             Map config,
                             BorderExtender extender,
                             AffineTransform transform,
                             Interpolation interp,
                             double[] backgroundValues) {
        super(vectorize(source),
              layoutHelper(layout,
                           source,
                           transform),
              config,
              true,
              extender,
              interp,
              backgroundValues);

        // store the interp and extender objects
        this.interp = interp;

        // the extender
        this.extender = extender;

        // cache the listener
        listener = ImageUtil.getImagingListener((java.awt.RenderingHints)config);

        // Store the padding values
        lpad = interp.getLeftPadding();
        rpad = interp.getRightPadding();
        tpad = interp.getTopPadding();
        bpad = interp.getBottomPadding();

        //
        // Store source bounds rectangle
        // and the padded rectangle (for extension cases)
        //
        srcimg = new Rectangle(getSourceImage(0).getMinX(),
                               getSourceImage(0).getMinY(),
                               getSourceImage(0).getWidth(),
                               getSourceImage(0).getHeight());
        padimg = new Rectangle(srcimg.x - lpad,
                               srcimg.y - tpad,
                               srcimg.width + lpad + rpad,
                               srcimg.height + tpad + bpad);

        if (extender == null) {
            //
            // Source has to be shrunk as per interpolation
            // as a result the destination produced could
            // be different from the layout
            //

            //
            // Get sx0,sy0 coordinates and width & height of the source
            //
            float sx0 = (float) srcimg.x;
            float sy0 = (float) srcimg.y;
            float sw = (float) srcimg.width;
            float sh = (float) srcimg.height;

            //
            // get padding amounts as per interpolation
            //
            float f_lpad = (float)lpad;
            float f_rpad = (float)rpad;
            float f_tpad = (float)tpad;
            float f_bpad = (float)bpad;

            //
            // As per pixel defined to be at (0.5, 0.5)
            //
            if ((interp instanceof InterpolationBilinear) ||
                (interp instanceof InterpolationBicubic) ||
                (interp instanceof InterpolationBicubic2)) {
                f_lpad += 0.5;
                f_tpad += 0.5;
                f_rpad += 0.5;
                f_bpad += 0.5;
            }

            //
            // Shrink the source by padding amount prior to forward map
            // This is the maxmimum available source than can be mapped
            //
            sx0 += f_lpad;
            sy0 += f_tpad;
            sw -= (f_lpad + f_rpad);
            sh -= (f_tpad + f_bpad);

            //
            // The 4 points are (x0, y0),     (x0+w, y0)
            //                  (x0+w, y0+h), (x0, y0+h)
            //
            Point2D[] pts = new Point2D[4];
            pts[0] = new Point2D.Float(sx0, sy0);
            pts[1] = new Point2D.Float((sx0 + sw), sy0);
            pts[2] = new Point2D.Float((sx0 + sw), (sy0 + sh));
            pts[3] = new Point2D.Float(sx0, (sy0 + sh));

            // Forward map
            transform.transform(pts, 0, pts, 0, 4);

            float dx0 = Float.MAX_VALUE;
            float dy0 = Float.MAX_VALUE;
            float dx1 = -Float.MAX_VALUE;
            float dy1 = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float px = (float)pts[i].getX();
                float py = (float)pts[i].getY();

                dx0 = Math.min(dx0, px);
                dy0 = Math.min(dy0, py);
                dx1 = Math.max(dx1, px);
                dy1 = Math.max(dy1, py);
            }

            //
            // The layout is the wholly contained integer area of the
            // corresponding floating point bounding box.
            // We cannot round the corners of the floating rect because it
            // would increase the size of the rect, so we need to ceil the
            // upper corner and floor the lower corner.
            //
            int lx0 = (int)Math.ceil(dx0);
            int ly0 = (int)Math.ceil(dy0);
            int lx1 = (int)Math.floor(dx1);
            int ly1 = (int)Math.floor(dy1);

            theDest = new Rectangle(lx0,
                                    ly0,
                                    lx1 - lx0,
                                    ly1 - ly0);
        } else {
            theDest = getBounds();
        }

        // Store the inverse and forward transform
        try {
            this.i_transform = transform.createInverse();
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            String message = JaiI18N.getString("MlibAffineOpImage0");
            listener.errorOccurred(message,
                                   new ImagingException(message, e),
                                   this,
                                   false);
//            throw new RuntimeException(JaiI18N.getString("MlibAffineOpImage0"));
        }
        this.transform = (AffineTransform)transform.clone();

        //
        // Get the forward transform into an array
        // Java returns the values into the array as
        // {m00 m10 m01 m11 m02 m12}
        //
        this.f_transform = new double[6];
        transform.getMatrix(this.f_transform);

        //
        // Rearrange the transform to medialib specifications
        // J2D's transform is [m00 m01 m02] [m10 m11 m12]
        // Medialib's transform is [a b tx] [c d ty]
        //
        this.medialib_tr = new double[6];
        medialib_tr[0] = f_transform[0]; // a  <---> m00
        medialib_tr[1] = f_transform[2]; // b  <---> m01
        medialib_tr[2] = f_transform[4]; // tx <---> m02
        medialib_tr[3] = f_transform[1]; // c  <---> m10
        medialib_tr[4] = f_transform[3]; // d  <---> m11
        medialib_tr[5] = f_transform[5]; // ty <---> m12

        //
        // Make a copy for our internal use
        //
        this.m_transform = new double[6];
        m_transform[0] = f_transform[0]; // a  <---> m00
        m_transform[1] = f_transform[2]; // b  <---> m01
        m_transform[2] = f_transform[4]; // tx <---> m02
        m_transform[3] = f_transform[1]; // c  <---> m10
        m_transform[4] = f_transform[3]; // d  <---> m11
        m_transform[5] = f_transform[5]; // ty <---> m12
    }


    /**
     * Computes the source point corresponding to the supplied point.
     *
     * @param destPt the position in destination image coordinates
     * to map to source image coordinates.
     *
     * @return a <code>Point2D</code> of the same class as
     * <code>destPt</code>.
     *
     * @throws IllegalArgumentException if <code>destPt</code> is
     * <code>null</code>.
     *
     * @since JAI 1.1.2
     */
    public Point2D mapDestPoint(Point2D destPt) {
        if (destPt == null) {
            throw new IllegalArgumentException(JaiI18N.getString("Generic0"));
        }

        Point2D dpt = (Point2D)destPt.clone();
        dpt.setLocation(dpt.getX() + 0.5, dpt.getY() + 0.5);

        Point2D spt = i_transform.transform(dpt, null);
        spt.setLocation(spt.getX() - 0.5, spt.getY() - 0.5);

        return spt;
    }

    /**
     * Computes the destination point corresponding to the supplied point.
     *
     * @param sourcePt the position in source image coordinates
     * to map to destination image coordinates.
     *
     * @return a <code>Point2D</code> of the same class as
     * <code>sourcePt</code>.
     *
     * @throws IllegalArgumentException if <code>destPt</code> is
     * <code>null</code>.
     *
     * @since JAI 1.1.2
     */
    public Point2D mapSourcePoint(Point2D sourcePt) {
        if (sourcePt == null) {
            throw new IllegalArgumentException(JaiI18N.getString("Generic0"));
        }

        Point2D spt = (Point2D)sourcePt.clone();
        spt.setLocation(spt.getX() + 0.5, spt.getY() + 0.5);

        Point2D dpt = transform.transform(spt, null);
        dpt.setLocation(dpt.getX() - 0.5, dpt.getY() - 0.5);

        return dpt;
    }

    /**
     * Forward map the source Rectangle.
     */
    protected Rectangle forwardMapRect(Rectangle sourceRect,
                                       int sourceIndex) {
        return transform.createTransformedShape(sourceRect).getBounds();
    }

    /**
     * Backward map the destination Rectangle.
     */
    protected Rectangle backwardMapRect(Rectangle destRect,
                                        int sourceIndex) {
        //
        // Backward map the destination rectangle to get the
        // corresponding source rectangle
        //
        float dx0 = (float) destRect.x;
        float dy0 = (float) destRect.y;
        float dw = (float) (destRect.width);
        float dh = (float) (destRect.height);

        Point2D[] pts = new Point2D[4];
        pts[0] = new Point2D.Float(dx0, dy0);
        pts[1] = new Point2D.Float((dx0 + dw), dy0);
        pts[2] = new Point2D.Float((dx0 + dw), (dy0 + dh));
        pts[3] = new Point2D.Float(dx0, (dy0 + dh));

        i_transform.transform(pts, 0, pts, 0, 4);

        float f_sx0 = Float.MAX_VALUE;
        float f_sy0 = Float.MAX_VALUE;
        float f_sx1 = -Float.MAX_VALUE;
        float f_sy1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float px = (float)pts[i].getX();
            float py = (float)pts[i].getY();

            f_sx0 = Math.min(f_sx0, px);
            f_sy0 = Math.min(f_sy0, py);
            f_sx1 = Math.max(f_sx1, px);
            f_sy1 = Math.max(f_sy1, py);
        }

        int s_x0 = 0, s_y0 = 0, s_x1 = 0, s_y1 = 0;

        // Find the bounding box of the source rectangle
        if (interp instanceof InterpolationNearest) {
            s_x0 = (int) Math.floor(f_sx0);
            s_y0 = (int) Math.floor(f_sy0);
            s_x1 = (int) Math.ceil(f_sx1);
            s_y1 = (int) Math.ceil(f_sy1);
        } else {
            s_x0 = (int) Math.floor(f_sx0 - 0.5);
            s_y0 = (int) Math.floor(f_sy0 - 0.5);
            s_x1 = (int) Math.ceil(f_sx1);
            s_y1 = (int) Math.ceil(f_sy1);
        }

        //
        // Return the new rectangle
        //
        return new Rectangle(s_x0,
                             s_y0,
                             s_x1 - s_x0,
                             s_y1 - s_y0);
    }

    /*
     * Compute a given tile
     */
    public Raster computeTile(int tileX, int tileY) {
        //
        // Create a new WritableRaster to represent this tile.
        //
        Point org = new Point(tileXToX(tileX), tileYToY(tileY));
        WritableRaster dest = createWritableRaster(sampleModel, org);

        //
        // Clip output rectangle to image bounds.
        //
        Rectangle rect = new Rectangle(org.x,
                                       org.y,
                                       tileWidth,
                                       tileHeight);

        //
        // Clip destination tile against the writable destination
        // area. This is either the layout or a smaller area if
        // no extension is specified.
        //
        Rectangle destRect = rect.intersection(theDest);
        Rectangle destRect1 = rect.intersection(getBounds());
        if ((destRect.width <= 0) || (destRect.height <= 0)) {
	    if (setBackground) {
		ImageUtil.fillBackground(dest, destRect1, backgroundValues);
	    }
            // No area to write
            return dest;
        }

        //
        // determine the source rectangle needed to compute the destRect
        //
        Rectangle srcRect = mapDestRect(destRect, 0);
        if (extender == null) {
            srcRect = srcRect.intersection(srcimg);
        } else {
            srcRect = srcRect.intersection(padimg);
        }

        if (srcRect.width <= 0 || srcRect.height <= 0) {
            // destRect backward mapped outside the source
	    if (setBackground) {
		ImageUtil.fillBackground(dest, destRect1, backgroundValues);
	    }
            return dest;
        }

        if (!destRect1.equals(destRect)) {
            // beware that destRect1 contains destRect
            ImageUtil.fillBordersWithBackgroundValues(destRect1, destRect, dest, backgroundValues);
        }

        Raster[] sources = new Raster[1];

        // Get the source data
        if (extender == null) {
            sources[0] = getSourceImage(0).getData(srcRect);
        } else {
            sources[0] = getSourceImage(0).getExtendedData(srcRect, extender);
        }

        computeRect(sources, dest, destRect);

        // Recycle the source tile
        if(getSourceImage(0).overlapsMultipleTiles(srcRect)) {
            recycleTile(sources[0]);
        }

        return dest;
    }
}
