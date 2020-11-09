package org.area515.resinprinter.job;

import java.awt.image.BufferedImage;
import java.io.File;

public interface PrintFileProcessor<G,E> {
	public String[] getFileExtensions();
	public String getFriendlyName();
	public boolean acceptsFile(File processingFile);
	public BufferedImage getCurrentImage(PrintJob printJob);
	public Double getBuildAreaMM(PrintJob printJob);
	public JobStatus processFile(PrintJob printJob) throws Exception;
	public void prepareEnvironment(File processingFile, PrintJob printJob) throws JobManagerException;
	public void cleanupEnvironment(File processingFile) throws JobManagerException;
	public boolean isThreeDimensionalGeometryAvailable();
	public G getGeometry(PrintJob printJob) throws JobManagerException;
	public E getErrors(PrintJob printJob) throws JobManagerException;
}
