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
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.Map;

import org.eclipse.imagen.ImageLayout;
import org.eclipse.imagen.PointOpImage;

// import org.eclipse.imagen.media.test.OpImageTester;
import com.sun.medialib.mlib.Image;
import com.sun.medialib.mlib.mediaLibImage;

/**
 * A mediaLib implementation of "AndConst" operator.
 *
 */
final class MlibAndConstOpImage extends PointOpImage {
    int[] constants;

    /**
     * Constructs an MlibAndConstOpImage. The image dimensions are copied
     * from the source image.  The tile grid layout, SampleModel, and
     * ColorModel may optionally be specified by an ImageLayout object.
     *
     * @param source    a RenderedImage.
     * @param layout    an ImageLayout optionally containing the tile
     *                  grid layout, SampleModel, and ColorModel, or null.
     */
    public MlibAndConstOpImage(RenderedImage source,
                               Map config,
                               ImageLayout layout,
                               int[] constants) {
        super(source, layout, config, true);
        this.constants = MlibUtils.initConstants(constants,
                                            getSampleModel().getNumBands());
        // Set flag to permit in-place operation.
        permitInPlaceOperation();
    }

    /**
     * And the pixel values of a rectangle with a given constant.
     * The sources are cobbled.
     *
     * @param sources   an array of sources, guarantee to provide all
     *                  necessary source data for computing the rectangle.
     * @param dest      a tile that contains the rectangle to be computed.
     * @param destRect  the rectangle within this OpImage to be processed.
     */
    protected void computeRect(Raster[] sources,
                               WritableRaster dest,
                               Rectangle destRect) {

        Raster source = sources[0];
        int formatTag = MediaLibAccessor.findCompatibleTag(sources, dest);

	// For PointOpImages, the srcRect and the destRect are the same.
        MediaLibAccessor srcAccessor = new MediaLibAccessor(source, destRect,
							    formatTag);
        MediaLibAccessor dstAccessor = new MediaLibAccessor(dest, destRect,
							    formatTag);

        switch (dstAccessor.getDataType()) {
        case DataBuffer.TYPE_BYTE:
        case DataBuffer.TYPE_USHORT:
        case DataBuffer.TYPE_SHORT:
        case DataBuffer.TYPE_INT:
            mediaLibImage[] srcML = srcAccessor.getMediaLibImages();
            mediaLibImage[] dstML = dstAccessor.getMediaLibImages();
            for (int i = 0 ; i < dstML.length; i++) {
                int mlconstants[] = dstAccessor.getIntParameters(i, constants);
                Image.ConstAnd(dstML[i], srcML[i], mlconstants);
            }
            break;
        default:
            String className = this.getClass().getName();
            throw new RuntimeException(className + JaiI18N.getString("Generic2"));
        }

        if (dstAccessor.isDataCopy()) {
            dstAccessor.clampDataArrays();
            dstAccessor.copyDataToRaster();
        }
    }

//     public static OpImage createTestImage(OpImageTester oit) {
//         int[] consts = {5, 5, 5};
//         return new MlibAndConstOpImage(oit.getSource(), null,
//                                        new ImageLayout(oit.getSource()),
//                                        consts);
//     }

//     // Calls a method on OpImage that uses introspection, to make this
//     // class, discover it's createTestImage() call, call it and then
//     // benchmark the performance of the created OpImage chain.
//     public static void main (String args[]) {
//         String classname = "org.eclipse.imagen.media.mlib.MlibAndConstOpImage";
//         OpImageTester.performDiagnostics(classname,args);
//     }
}
