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
 * An OpImage that performs the Add operation on 2 images through mediaLib.
 *
 */
final class MlibAddOpImage extends PointOpImage {

    // XXX This cloning of the ImageLayout object should be centraliZed
    // into the superclass PointOpImage and removed from the mlib subclasses.
    private static ImageLayout layoutHelper(ImageLayout layout) {
	if (layout == null) {
	    return null;
	} else {
	    return (ImageLayout)layout.clone();
	}
    }

    /**
     * Constructs an MlibAddOpImage. The image dimensions are copied
     * from the source image.  The tile grid layout, SampleModel, and
     * ColorModel may optionally be specified by an ImageLayout object.
     *
     * @param source    a RenderedImage.
     * @param layout    an ImageLayout optionally containing the tile
     *                  grid layout, SampleModel, and ColorModel, or null.
     */
    public MlibAddOpImage(RenderedImage source1, RenderedImage source2,
                          Map config,
			  ImageLayout  layout) {
	super(source1, source2, layoutHelper(layout), config, true);
        // Set flag to permit in-place operation.
        permitInPlaceOperation();
    }

    /**
     * Add the pixel values of a rectangle from the two sources.
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

        int formatTag = MediaLibAccessor.findCompatibleTag(sources,dest);

        MediaLibAccessor srcAccessor1 = 
            new MediaLibAccessor(sources[0], destRect,formatTag);
	MediaLibAccessor srcAccessor2 = 
	    new MediaLibAccessor(sources[1], destRect,formatTag);
        MediaLibAccessor dstAccessor = 
            new MediaLibAccessor(dest, destRect, formatTag);

        mediaLibImage[] srcML1 = srcAccessor1.getMediaLibImages();
        mediaLibImage[] srcML2 = srcAccessor2.getMediaLibImages();
        mediaLibImage[] dstML  = dstAccessor.getMediaLibImages();

        switch (dstAccessor.getDataType()) {
        case DataBuffer.TYPE_BYTE:
        case DataBuffer.TYPE_USHORT:
        case DataBuffer.TYPE_SHORT:
        case DataBuffer.TYPE_INT:
            for (int i = 0 ; i < dstML.length; i++) {
                Image.Add(dstML[i], srcML1[i], srcML2[i]);
            }
            break;

        case DataBuffer.TYPE_FLOAT:
        case DataBuffer.TYPE_DOUBLE:
            for (int i = 0 ; i < dstML.length; i++) {
                Image.Add_Fp(dstML[i], srcML1[i], srcML2[i]);
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

//     public static void main (String args[]) {
//         System.out.println("MlibAddOpImage Test");
//         ImageLayout layout;
//         OpImage src1, src2, dst;
//         Rectangle rect = new Rectangle(0, 0, 5, 5);

//         System.out.println("1. PixelInterleaved byte 3-band");
//         layout = OpImageTester.createImageLayout(0, 0, 800, 800, 0, 0,
// 						 200, 200, DataBuffer.TYPE_BYTE,
// 						 3, false);
//         src1 = OpImageTester.createRandomOpImage(layout);
//         src2 = OpImageTester.createRandomOpImage(layout);
//         dst = new MlibAddOpImage(src1, src2, null, null);
//         OpImageTester.testOpImage(dst, rect);
//         OpImageTester.timeOpImage(dst, 10);

//         System.out.println("2. Banded byte 3-band");
//         layout = OpImageTester.createImageLayout(0, 0, 800, 800, 0, 0,
// 						 200, 200, DataBuffer.TYPE_BYTE,
// 						 3, true);
//         src1 = OpImageTester.createRandomOpImage(layout);
//         src2 = OpImageTester.createRandomOpImage(layout);
//         dst = new MlibAddOpImage(src1, src2, null, null);
//         OpImageTester.testOpImage(dst, rect);
//         OpImageTester.timeOpImage(dst, 10);

//         System.out.println("3. PixelInterleaved int 3-band");
//         layout = OpImageTester.createImageLayout(0, 0, 512, 512, 0, 0, 200, 200,
// 						 DataBuffer.TYPE_INT, 3, false);
//         src1 = OpImageTester.createRandomOpImage(layout);
//         src2 = OpImageTester.createRandomOpImage(layout);
//         dst = new MlibAddOpImage(src1, src2, null, null);
//         OpImageTester.testOpImage(dst, rect);
//         OpImageTester.timeOpImage(dst, 10);

//         System.out.println("4. Banded int 3-band");
//         layout = OpImageTester.createImageLayout(0, 0, 512, 512, 0, 0,
// 						 200, 200, DataBuffer.TYPE_INT,
// 						 3, true);
//         src1 = OpImageTester.createRandomOpImage(layout);
//         src2 = OpImageTester.createRandomOpImage(layout);
//         dst = new MlibAddOpImage(src1, src2, null, null);
//         OpImageTester.testOpImage(dst, rect);
//         OpImageTester.timeOpImage(dst, 10);
//     }
}
