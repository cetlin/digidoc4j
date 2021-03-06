/* DigiDoc4J library
*
* This software is released under either the GNU Library General Public
* License (see LICENSE.LGPL).
*
* Note that the only valid version of the LGPL license as far as this
* project is concerned is the original GNU Library General Public License
* Version 2.1, February 1999
*/

package org.digidoc4j.impl.bdoc.asic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.digidoc4j.DataFile;
import org.digidoc4j.Signature;
import org.digidoc4j.exceptions.NotSupportedException;
import org.digidoc4j.exceptions.TechnicalException;
import org.digidoc4j.impl.bdoc.manifest.AsicManifest;
import org.digidoc4j.utils.Helper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.europa.esig.dss.DSSDocument;
import eu.europa.esig.dss.MimeType;

public class AsicContainerCreator {

  private static final Logger logger = LoggerFactory.getLogger(AsicContainerCreator.class);

  private static final String ZIP_ENTRY_MIMETYPE = "mimetype";
  private static final Charset CHARSET = StandardCharsets.UTF_8;

  private final ZipOutputStream zipOutputStream;
  private final OutputStream outputStream;

  private String zipComment;

  @Deprecated
  public AsicContainerCreator(File containerPathToSave) {
    this(Helper.bufferedOutputStream(containerPathToSave));
  }

  public AsicContainerCreator(OutputStream outputStream) {
    this.outputStream = outputStream;
    this.zipOutputStream = new ZipOutputStream(outputStream, CHARSET);
  }

  @Deprecated
  public AsicContainerCreator() {
    this(new ByteArrayOutputStream());
  }

  public void finalizeZipFile() {
    logger.debug("Finalizing bdoc zip file");
    try {
      zipOutputStream.finish();
    } catch (IOException e) {
      handleIOException("Unable to finish creating BDoc ZIP container", e);
    }
  }

  @Deprecated
  public InputStream fetchInputStreamOfFinalizedContainer() {
    if (outputStream instanceof ByteArrayOutputStream) {
      logger.debug("Fetching input stream of the finalized container");
      return new ByteArrayInputStream(((ByteArrayOutputStream) outputStream).toByteArray());
    }
    throw new NotSupportedException("instance not backed by an in-memory stream");
  }


  public void writeAsiceMimeType() {
    logger.debug("Writing asic mime type to bdoc zip file");
    String mimeTypeString = MimeType.ASICE.getMimeTypeString();
    byte[] mimeTypeBytes = mimeTypeString.getBytes(CHARSET);
    new BytesEntryCallback(getAsicMimeTypeZipEntry(mimeTypeBytes), mimeTypeBytes).write();
  }

  public void writeManifest(Collection<DataFile> dataFiles) {
    logger.debug("Writing bdoc manifest");
    final AsicManifest manifest = new AsicManifest();
    manifest.addFileEntry(dataFiles);
    new EntryCallback(new ZipEntry(AsicManifest.XML_PATH)) {
      @Override
      void doWithEntryStream(OutputStream stream) throws IOException {
        manifest.writeTo(stream);
      }
    }.write();
  }

  public void writeDataFiles(Collection<DataFile> dataFiles) {
    logger.debug("Adding data files to the bdoc zip container");
    for (DataFile dataFile : dataFiles) {
      String name = dataFile.getName();
      logger.debug("Adding data file {}", name);
      zipOutputStream.setLevel(ZipEntry.DEFLATED);
      new StreamEntryCallback(new ZipEntry(name), dataFile.getStream()).write();
    }
  }

  public void writeSignatures(Collection<Signature> signatures, int nextSignatureFileNameIndex) {
    logger.debug("Adding signatures to the bdoc zip container");
    int index = nextSignatureFileNameIndex;
    for (Signature signature : signatures) {
      String signatureFileName = "META-INF/signatures" + index + ".xml";
      new BytesEntryCallback(new ZipEntry(signatureFileName), signature.getAdESSignature()).write();
      index++;
    }
  }

  public void writeExistingEntries(Collection<AsicEntry> asicEntries) {
    logger.debug("Writing existing zip container entries");
    for (AsicEntry asicEntry : asicEntries) {
      DSSDocument content = asicEntry.getContent();
      ZipEntry zipEntry = asicEntry.getZipEntry();
      if (!StringUtils.equalsIgnoreCase(ZIP_ENTRY_MIMETYPE, zipEntry.getName())) {
        zipOutputStream.setLevel(ZipEntry.DEFLATED);
      }
      new StreamEntryCallback(zipEntry, content.openStream(), false).write();
    }
  }

  public void writeContainerComment(String comment) {
    logger.debug("Writing container comment: " + comment);
    zipOutputStream.setComment(comment);
  }

  public void setZipComment(String zipComment) {
    this.zipComment = zipComment;
  }

  private class StreamEntryCallback extends EntryCallback {

    private final InputStream inputStream;

    StreamEntryCallback(ZipEntry entry, InputStream inputStream) {
      this(entry, inputStream, true);
    }

    StreamEntryCallback(ZipEntry entry, InputStream inputStream, boolean addComment) {
      super(entry, addComment);
      this.inputStream = inputStream;
    }

    @Override
    void doWithEntryStream(OutputStream stream) throws IOException {
      IOUtils.copy(inputStream, stream);
    }

  }

  private class BytesEntryCallback extends EntryCallback {

    private final byte[] data;

    BytesEntryCallback(ZipEntry entry, byte[] data) {
      super(entry);
      this.data = data;
    }

    @Override
    void doWithEntryStream(OutputStream stream) throws IOException {
      stream.write(data);
    }

  }

  private abstract class EntryCallback {

    private final ZipEntry entry;
    private final boolean addComment;

    EntryCallback(ZipEntry entry) {
      this(entry, true);
    }

    EntryCallback(ZipEntry entry, boolean addComment) {
      this.entry = entry;
      this.addComment = addComment;
    }

    void write() {
      if (addComment) {
        entry.setComment(zipComment);
      }

      try {
        zipOutputStream.putNextEntry(entry);
        doWithEntryStream(zipOutputStream);
        zipOutputStream.closeEntry();
      } catch (IOException e) {
        handleIOException("Unable to write Zip entry to BDoc container", e);
      }
    }

    abstract void doWithEntryStream(OutputStream stream) throws IOException;

  }

  private static ZipEntry getAsicMimeTypeZipEntry(byte[] mimeTypeBytes) {
    ZipEntry entryMimetype = new ZipEntry(ZIP_ENTRY_MIMETYPE);
    entryMimetype.setMethod(ZipEntry.STORED);
    entryMimetype.setSize(mimeTypeBytes.length);
    entryMimetype.setCompressedSize(mimeTypeBytes.length);
    CRC32 crc = new CRC32();
    crc.update(mimeTypeBytes);
    entryMimetype.setCrc(crc.getValue());
    return entryMimetype;
  }

  private static void handleIOException(String message, IOException e) {
    logger.error(message + ": " + e.getMessage());
    throw new TechnicalException(message, e);
  }

}
