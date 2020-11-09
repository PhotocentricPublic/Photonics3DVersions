package org.area515.resinprinter.stream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.area515.resinprinter.services.MediaService;

public class ProgressiveDownloadServlet extends HttpServlet {
    private static final Logger logger = LogManager.getLogger();
	private static final long serialVersionUID = 5110548757293069069L;
	
	public ProgressiveDownloadServlet() {
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		logger.info("HTTPGet:{}", request.getRequestURI());
		doAll(request, response);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		logger.info("HTTPPost:{}", request.getRequestURI());
		doAll(request, response);
	}
	
	private void doAll(HttpServletRequest request, HttpServletResponse response) {
		File servedFile = MediaService.INSTANCE.getRecordedFile();
		Path path = servedFile != null?servedFile.toPath():null;

		if (servedFile == null || path == null || !path.toFile().exists()) {
			if (servedFile == null) {
				logger.warn("Couldn't find resource:{}", request.getRequestURI());
			} else {
				logger.warn("File doesn't exist:{} for resource:{}", path, request.getRequestURL());
			}
			try {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			} catch (IOException e) {
				logger.error("Couldn't send error back to client for path:" + request.getPathInfo(), e);
			}
			return;
		}
		
		OutputStream outputStream = null;
		SeekableByteChannel input = null;
        try {
    		//lock.lock();

			long fileSize = Files.size(path);
	    	if (fileSize == 0) {
	    		logger.warn("Filesize for:{} is 0. This usually means that you're streaming an invalid file. You may want to checkout the process that created this file.", path);
	    	}
	    	Pattern rangePattern = Pattern.compile("bytes=(\\d*)-(\\d*)");
	        response.setContentType(Files.probeContentType(path));
	        response.setHeader("Accept-Ranges", "bytes");
	        response.setHeader("TransferMode.DLNA.ORG", "Streaming");
	        response.setHeader("File-Size", fileSize + "");
	        
	        if (logger.isDebugEnabled()) {
		        Enumeration<String> headerNames = request.getHeaderNames();
		    	while (headerNames.hasMoreElements()) {
		    		String name = headerNames.nextElement();
		    		logger.debug("{}:{}", name, request.getHeader(name));
		    	}
	        }
	        
	        OutputStream outStream = response.getOutputStream();
	        String rangeValue = request.getHeader("Range");
	        long start = 0;
	        long end = response.getBufferSize() - 1;
	        if (rangeValue != null) {
		        Matcher matcher = rangePattern.matcher(rangeValue);
		        if (matcher.matches()) {
		            String startGroup = matcher.group(1);
		            start = startGroup == null || startGroup.isEmpty() ? start : Integer.valueOf(startGroup);
		            start = start < 0 ? 0 : start;
		       
		            String endGroup = matcher.group(2);
		            end = endGroup == null || endGroup.isEmpty() ? (start + end < fileSize?start + end:fileSize - 1): Integer.valueOf(endGroup);
	                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
		        } else {
		        	rangeValue = null;
		        }
	        }
	        response.setStatus(rangeValue == null? HttpServletResponse.SC_OK:HttpServletResponse.SC_PARTIAL_CONTENT); // 206.
	        
	        int bytesRead;
	        long bytesLeft = rangeValue == null? fileSize: (int)(end - start + 1);
	        response.setContentLength((int)bytesLeft);
	        ByteBuffer buffer = ByteBuffer.allocate(response.getBufferSize());
	        input = Files.newByteChannel(path, StandardOpenOption.READ);
	        OutputStream output = response.getOutputStream();
	        input.position(start);
	        while ((bytesRead = input.read(buffer)) != -1 && bytesLeft > 0) {
	            buffer.clear();
	            output.write(buffer.array(), 0, bytesLeft < bytesRead ? (int)bytesLeft : bytesRead);
	            bytesLeft -= bytesRead;
	        }
	        
            outStream.flush();
        } catch (IOException e) {
        	logger.error("Error handling file:" + path, e);
        } finally {
			if (input != null)
				try {input.close();} catch (IOException e) {}
			
			if (outputStream != null)
				try {outputStream.flush();} catch (IOException e) {}
			
			//lock.unlock();
		}
	}
}
