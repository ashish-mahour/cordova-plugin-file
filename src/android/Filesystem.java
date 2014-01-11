package org.apache.cordova.file;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public abstract class Filesystem {

	public interface ReadFileCallback {
		public void handleData(InputStream inputStream, String contentType) throws IOException;
	}

	public static JSONObject makeEntryForPath(String path, int fsType, Boolean isDir)
			throws JSONException {
        JSONObject entry = new JSONObject();

        int end = path.endsWith("/") ? 1 : 0;
        String[] parts = path.substring(0,path.length()-end).split("/");
        String name = parts[parts.length-1];
        entry.put("isFile", !isDir);
        entry.put("isDirectory", isDir);
        entry.put("name", name);
        entry.put("fullPath", path);
        // The file system can't be specified, as it would lead to an infinite loop,
        // but the filesystem type can
        entry.put("filesystem", fsType);

        return entry;

    }

    public static JSONObject makeEntryForURL(LocalFilesystemURL inputURL, Boolean isDir) throws JSONException {
        return makeEntryForPath(inputURL.fullPath, inputURL.filesystemType, isDir);
    }

	abstract JSONObject getEntryForLocalURL(LocalFilesystemURL inputURL) throws IOException;

	abstract JSONObject getFileForLocalURL(LocalFilesystemURL inputURL, String path,
			JSONObject options, boolean directory) throws FileExistsException, IOException, TypeMismatchException, EncodingException, JSONException;

	abstract boolean removeFileAtLocalURL(LocalFilesystemURL inputURL) throws InvalidModificationException, NoModificationAllowedException;

	abstract boolean recursiveRemoveFileAtLocalURL(LocalFilesystemURL inputURL) throws FileExistsException, NoModificationAllowedException;

	abstract JSONArray readEntriesAtLocalURL(LocalFilesystemURL inputURL) throws FileNotFoundException;

	abstract JSONObject getFileMetadataForLocalURL(LocalFilesystemURL inputURL) throws FileNotFoundException;

	public JSONObject getParentForLocalURL(LocalFilesystemURL inputURL) throws IOException {
		LocalFilesystemURL newURL = new LocalFilesystemURL(inputURL.URL);
	
		if (!("".equals(inputURL.fullPath) || "/".equals(inputURL.fullPath))) {
			int end = inputURL.fullPath.endsWith("/") ? 1 : 0;
	        int lastPathStartsAt = inputURL.fullPath.lastIndexOf('/', inputURL.fullPath.length()-end)+1;
			newURL.fullPath = newURL.fullPath.substring(0,lastPathStartsAt);
		}
		return getEntryForLocalURL(newURL);
	}

    protected LocalFilesystemURL makeDestinationURL(String newName, LocalFilesystemURL srcURL, LocalFilesystemURL destURL) {
        // I know this looks weird but it is to work around a JSON bug.
        if ("null".equals(newName) || "".equals(newName)) {
            newName = srcURL.URL.getLastPathSegment();;
        }

        String newDest = destURL.URL.toString();
        if (newDest.endsWith("/")) {
            newDest = newDest + newName;
        } else {
            newDest = newDest + "/" + newName;
        }
        return new LocalFilesystemURL(newDest);
    }
    
	/* Read a source URL (possibly from a different filesystem, srcFs,) and copy it to
	 * the destination URL on this filesystem, optionally with a new filename.
	 * If move is true, then this method should either perform an atomic move operation
	 * or remove the source file when finished.
	 */
    JSONObject copyFileToURL(LocalFilesystemURL destURL, String newName,
            Filesystem srcFs, LocalFilesystemURL srcURL, boolean move) throws IOException, InvalidModificationException, JSONException, NoModificationAllowedException, FileExistsException {
        // This is "the hard way" -- transfer data between arbitrary filesystem urls/
        // Gets an input stream from src, and writes its contents to an output stream
        // from dest.

        // First, check to see that we can do it
        if (!move || srcFs.canRemoveFileAtLocalURL(srcURL)) {
            final LocalFilesystemURL destination = makeDestinationURL(newName, srcURL, destURL);
            srcFs.readFileAtURL(srcURL, 0, -1, new ReadFileCallback() {
                public void handleData(InputStream inputStream, String contentType) throws IOException {
                    if (inputStream != null) {
                        //write data to file
                        OutputStream os = getOutputStreamForURL(destination);
                        final int BUFFER_SIZE = 8192;
                        byte[] buffer = new byte[BUFFER_SIZE];

                        for (;;) {
                            int bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE);

                            if (bytesRead <= 0) {
                                break;
                            }
                            os.write(buffer, 0, bytesRead);
                        }
                        os.close();
                    } else {
                        throw new IOException("Cannot read file at source URL");
                    }
                }
            });
            if (move) {
                // Delete original
                srcFs.removeFileAtLocalURL(srcURL);
            }
            return makeEntryForURL(destination, false);
        } else {
            throw new NoModificationAllowedException("Cannot move file at source URL");
        }
    }

    abstract OutputStream getOutputStreamForURL(LocalFilesystemURL inputURL) throws IOException;

    abstract void readFileAtURL(LocalFilesystemURL inputURL, long start, long end,
			ReadFileCallback readFileCallback) throws IOException;

	abstract long writeToFileAtURL(LocalFilesystemURL inputURL, String data, int offset,
			boolean isBinary) throws NoModificationAllowedException, IOException;

	abstract long truncateFileAtURL(LocalFilesystemURL inputURL, long size)
			throws IOException, NoModificationAllowedException;

	// This method should return null if filesystem urls cannot be mapped to paths
	abstract String filesystemPathForURL(LocalFilesystemURL url);

	abstract LocalFilesystemURL URLforFilesystemPath(String path);

	abstract boolean canRemoveFileAtLocalURL(LocalFilesystemURL inputURL);

    protected class LimitedInputStream extends FilterInputStream {
        long numBytesToRead;
        public LimitedInputStream(InputStream in, long numBytesToRead) {
            super(in);
            this.numBytesToRead = numBytesToRead;
        }
        @Override
        public int read() throws IOException {
            if (numBytesToRead <= 0) {
                return -1;
            }
            numBytesToRead--;
            return in.read();
        }
        @Override
        public int read(byte[] buffer, int byteOffset, int byteCount) throws IOException {
            if (numBytesToRead <= 0) {
                return -1;
            }
            int bytesToRead = byteCount;
            if (byteCount > numBytesToRead) {
                bytesToRead = (int)numBytesToRead; // Cast okay; long is less than int here.
            }
            int numBytesRead = in.read(buffer, byteOffset, bytesToRead);
            numBytesToRead -= numBytesRead;
            return numBytesRead;
        }
    }

}
