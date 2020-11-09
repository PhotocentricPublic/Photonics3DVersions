package org.area515.resinprinter.job;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CreationWorkshopImageCache extends Thread {
    private static final Logger logger = LogManager.getLogger();

    // Last slice index, starting at zero.
    private AtomicInteger lastSliceIndex = new AtomicInteger(0);
    // Max. number of images cached. WARNING: not too big to avoid Out Of Memory!
    private AtomicInteger cacheMaxSize = new AtomicInteger(2);
    // Cache, with image index as key.
    private HashMap<Integer, BufferedImage> cache = new HashMap<Integer, BufferedImage>();
    private ReentrantLock cacheLock = new ReentrantLock(true);

    // Image transform operation, after loading.
    UnaryOperator<BufferedImage> imageTransformOp;

    // Image filename structure
    private File parentFile;
    private String baseFilename;
    private String extension = new String(".png");
    private int padLength;

    public CreationWorkshopImageCache(File parentFile, String baseFilename, int padLength, UnaryOperator<BufferedImage> imageTransformOp) {
        this.parentFile = parentFile;
        this.baseFilename = baseFilename;
        this.padLength = padLength;
        this.imageTransformOp = imageTransformOp;
    }

    @Override
    public void run() {
        int cacheSliceIndex = lastSliceIndex.get();
        // Main thread loop adding image to the cache.
        while (cacheMaxSize.get() > 0) {
            // Cache a new image if last slice index has increased.
            if (cacheSliceIndex < lastSliceIndex.get() + cacheMaxSize.get()) {
                logger.info("Thread caching image with index: {}.", cacheSliceIndex);
                addImageToCache(cacheSliceIndex);
                cacheSliceIndex++;
            }
            // Wait a bit until next image to cache!
            try {
                Thread.sleep(100);
            }
            catch(Exception e) {
            }
        }
        logger.info("Close image caching thread.");
    }

    public void close() throws InterruptedException {
        // Close caching thread.
        cacheMaxSize.set(0);
        this.join();
    }

    private File getImageFile(int sliceIndex) {
        String sliceNumber = String.format("%0" + this.padLength + "d", sliceIndex);
        String imageFilename = this.baseFilename + sliceNumber + this.extension;
        File imageFile = new File(this.parentFile, imageFilename);
        return imageFile;
    }

    private BufferedImage loadImageAndTransform(int sliceIndex) {
        File imageFile = getImageFile(sliceIndex);
        logger.info("Loading & transforming image from file: {}.", imageFile.getName());
        BufferedImage image;
        try {
            image = ImageIO.read(imageFile);
            image = imageTransformOp.apply(image);
        }
        catch(IOException e) {
            image = null;
            logger.error("IO error while loading image from file: {}.", imageFile.getName());
        }
        return image;
    }

    private void addImageToCache(int sliceIndex) {
        cacheLock.lock();
        try {
            if (!cache.containsKey(sliceIndex))
            {
                BufferedImage image = loadImageAndTransform(sliceIndex);
                cache.put(sliceIndex, image);
            }
        } finally {
            cacheLock.unlock();
        }
    }

    public BufferedImage getCachedOrLoadImage(int sliceIndex) {
        BufferedImage image;
        cacheLock.lock();
        logger.info("Get or load cached image with index: {}.", sliceIndex);
        try {
            if (cache.containsKey(sliceIndex))
            {
                // Image already in cache.
                image = cache.get(sliceIndex);
                cache.remove(sliceIndex);
            }
            else {
                image = loadImageAndTransform(sliceIndex);
            }
            this.lastSliceIndex.set(sliceIndex);
        } finally {
            cacheLock.unlock();
        }
        return image;
    }
}