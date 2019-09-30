/*
 * Copyright (C) 2005-2014 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.solr.content;

import org.alfresco.repo.content.ContentContext;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.solr.AlfrescoSolrDataModel;
import org.alfresco.solr.client.NodeMetaData;
import org.alfresco.solr.config.ConfigUtil;
import org.alfresco.solr.handler.ReplicationHandler;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.core.SolrResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static java.util.Collections.emptyList;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.alfresco.solr.content.SolrContentUrlBuilder.FILE_EXTENSION;

/**
 * A content store specific to SOLR's requirements: The URL is generated from a
 * set of properties such as:
 * <ul>
 * <li>ACL ID</li>
 * <li>DB ID</li>
 * <li>Other metadata</li>
 * </ul>
 * The URL, if not known, can be reliably regenerated using the
 * {@link SolrContentUrlBuilder}.
 * 
 * @author Derek Hulley
 * @author Michael Suzuki
 * @author Andrea Gazzarini
 * @since 5.0
 */
public class SolrContentStore implements ContentStore
{
    protected final static Logger log = LoggerFactory.getLogger(SolrContentStore.class);

    static final String CONTENT_STORE = "contentstore";
    static final String SOLR_CONTENT_DIR = "solr.content.dir";

    private final Predicate<File> onlyDatafiles = file -> file.isFile() && file.getName().endsWith(FILE_EXTENSION);
    private final String root;
    private final ChangeSet changes;

    /**
     * Builds a new {@link SolrContentStore} instance with the given SOLR HOME.
     *
     * @param solrHome the Solr HOME.
     */
    public SolrContentStore(String solrHome)
    {
        if (solrHome == null || solrHome.isEmpty())
        {
            throw new RuntimeException("Path to SOLR_HOME is required");
        }

        File solrHomeFile = new File(SolrResourceLoader.normalizeDir(solrHome));
        if (!solrHomeFile.exists())
        {
            //Its very unlikely that solrHome would not exist so we will log an error
            //but continue because solr.content.dir may be specified, so it keeps working
            log.error(solrHomeFile.getAbsolutePath() + " does not exist.");
        }
        
        String path = solrHomeFile.getParent() + "/" + CONTENT_STORE;
        log.warn(path + " will be used as a default path if " + SOLR_CONTENT_DIR + " property is not defined");
        File rootFile = new File(ConfigUtil.locateProperty(SOLR_CONTENT_DIR, path));

        try
        {
            FileUtils.forceMkdir(rootFile);
        } 
        catch (Exception e)
        {
            throw new RuntimeException("Failed to create directory for content store: " + rootFile, e);
        }
        this.root = rootFile.getAbsolutePath();

        changes = new ChangeSet.Builder().withContentStoreRoot(root).build();
    }

    /**
     * Returns the content store changes associated with the given index commit.
     *
     * @param commit the {@link IndexCommit} instance used as reference.
     * @return the content store changes associated with the given index commit.
     */
    public Map<String, List<Map<String, Object>>> getChanges(IndexCommit commit)
    {
        return Map.of(
                "adds", newFilesSince(commit),
                "deletes", deletedFilesSince(commit));
    }

    /**
     * Returns the content store deletes associated with the given index commit.
     *
     * @param commit the {@link IndexCommit} instance used as reference.
     * @return the content store deletes associated with the given index commit.
     *
     * TODO: IMPLEMENT
     */
    private List<Map<String, Object>> deletedFilesSince(IndexCommit commit)
    {
        return emptyList();
    }

    /**
     * Returns the content store adds/updates associated with the given index commit.
     *
     * @param commit the {@link IndexCommit} instance used as reference.
     * @return the content store adds/updates associated with the given index commit.
     *
     * TODO: IMPLEMENT
     */
    private List<Map<String, Object>> newFilesSince(IndexCommit commit)
    {
        try
        {
            return Files.walk(Paths.get(root))
                    .map(Path::toFile)
                    .filter(onlyDatafiles)
                    .map(file -> new ReplicationHandler.FileInfo(file, file.getAbsolutePath().replace(root, "")))
                    .map(ReplicationHandler.FileInfo::getAsMap)
                    .collect(toList());
        } catch (Exception e) {
            log.error(
                    "An exception occurred while creating the ContentStore filelist associated with index version {}. " +
                            "As consequence of that an empty list will be returned (i.e. no ContentStore synch will happen).",
                    ReplicationHandler.CommitVersionInfo.build(commit));
            return emptyList();
        }
    }

    // write a BytesRef as a byte array
    private JavaBinCodec.ObjectResolver resolver = (o, codec) -> {
        if(o instanceof BytesRef)
        {
            BytesRef br = (BytesRef)o;
            codec.writeByteArray(br.bytes,br.offset,br.length);
            return null;
        }
        return o;
    };

    /**
     * Retrieve document from SolrContentStore.
     *
     * @param tenant identifier
     * @param dbId identifier
     * @return {@link SolrInputDocument} searched document
     */
    public SolrInputDocument retrieveDocFromSolrContentStore(String tenant, long dbId)
    {
        String contentUrl =
                SolrContentUrlBuilder.start()
                        .add(SolrContentUrlBuilder.KEY_TENANT, tenant)
                        .add(SolrContentUrlBuilder.KEY_DB_ID, String.valueOf(dbId))
                        .get();

        ContentReader reader = this.getReader(contentUrl);
        if (!reader.exists())
        {
            return null;
        }

        try (InputStream contentInputStream = reader.getContentInputStream();
             InputStream gzip = new GZIPInputStream(contentInputStream))
        {
            return (SolrInputDocument) new JavaBinCodec(resolver).unmarshal(gzip);
        }
        catch (Exception exception)
        {
            // Don't fail for this
            log.warn("Failed to get doc from store using URL: " + contentUrl, exception);
            return null;
        }
    }

    @Override
    public boolean isContentUrlSupported(String contentUrl)
    {
        return (contentUrl != null && contentUrl.startsWith(SolrContentUrlBuilder.SOLR_PROTOCOL_PREFIX));
    }

    @Override
    public boolean isWriteSupported()
    {
        return true;
    }

    @Override
    public long getSpaceFree()
    {
        return -1L;
    }

    @Override
    public long getSpaceTotal()
    {
        return -1L;
    }

    /**
     * Returns the absolute path of the content store root folder.
     *
     * @return the absolute path of the content store root folder.
     */
    @Override
    public String getRootLocation()
    {
        return root;
    }

    /**
     * Convert a content URL into a File, whether it exists or not
     */
    private File getFileFromUrl(String contentUrl)
    {
        return new File(contentUrl.replace(SolrContentUrlBuilder.SOLR_PROTOCOL_PREFIX, root + "/"));
    }

    @Override
    public boolean exists(String contentUrl)
    {
        File file = getFileFromUrl(contentUrl);
        return file.exists();
    }

    @Override
    public ContentReader getReader(String contentUrl)
    {
        File file = getFileFromUrl(contentUrl);
        return new SolrFileContentReader(file, contentUrl);
    }

    @Override
    public ContentWriter getWriter(ContentContext context)
    {
        String url = context.getContentUrl();
        File file = getFileFromUrl(url);
        return new SolrFileContentWriter(file, url);
    }

    @Override
    public boolean delete(String contentUrl)
    {
        File file = getFileFromUrl(contentUrl);

        // TODO: Asynch
        boolean deleted = file.delete();

        if (deleted) changes.delete(relativePath(file));

        return deleted;
    }
    /**
     * Stores a {@link SolrInputDocument} into Alfresco solr content store.
     *
     * @param tenant the owning tenant.
     * @param dbId the document DBID
     * @param doc the document itself.
     */
    public void storeDocOnSolrContentStore(String tenant, long dbId, SolrInputDocument doc)
    {
        ContentContext contentContext =
                of(SolrContentUrlBuilder
                            .start()
                            .add(SolrContentUrlBuilder.KEY_TENANT, tenant)
                            .add(SolrContentUrlBuilder.KEY_DB_ID, String.valueOf(dbId)))
                   .map(SolrContentUrlBuilder::getContentContext)
                   .orElseThrow(() -> new IllegalArgumentException("Unable to build a Content Context from tenant " + tenant + " and DBID " + dbId));

        this.delete(contentContext.getContentUrl());

        ContentWriter writer = this.getWriter(contentContext);

        log.debug("Writing {}/{} to {}", tenant, dbId, contentContext.getContentUrl());

        try (OutputStream contentOutputStream = writer.getContentOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(contentOutputStream))
        {
            JavaBinCodec codec = new JavaBinCodec(resolver);
            codec.marshal(doc, gzip);

            // TODO: Asynch
            File file = getFileFromUrl(contentContext.getContentUrl());
            changes.addOrReplace(relativePath(file));
        }
        catch (Exception exception)
        {
            log.warn("Unable to write to Content Store using URL: {}", contentContext.getContentUrl(), exception);
        }
    }

    /**
     * Store {@link SolrInputDocument} in to Alfresco solr content store.
     *
     * @param nodeMetaData the incoming node metadata.
     * @param doc the document itself.
     */
    public void storeDocOnSolrContentStore(NodeMetaData nodeMetaData, SolrInputDocument doc)
    {
        String fixedTenantDomain = AlfrescoSolrDataModel.getTenantId(nodeMetaData.getTenantDomain());
        storeDocOnSolrContentStore(fixedTenantDomain, nodeMetaData.getId(), doc);
    }

    /**
     * Removes {@link SolrInputDocument} from Alfresco solr content store.
     *
     * @param nodeMetaData the incoming node metadata.
     */
    public void removeDocFromContentStore(NodeMetaData nodeMetaData)
    {
        String fixedTenantDomain = AlfrescoSolrDataModel.getTenantId(nodeMetaData.getTenantDomain());
        String contentUrl = SolrContentUrlBuilder
                    .start()
                    .add(SolrContentUrlBuilder.KEY_TENANT, fixedTenantDomain)
                    .add(SolrContentUrlBuilder.KEY_DB_ID, String.valueOf(nodeMetaData.getId()))
                    .getContentContext()
                .getContentUrl();
        this.delete(contentUrl);
    }

    /**
     * Assuming the input file belongs to the content store, it returns the corresponding relative path.
     *
     * @param file the content store file.
     * @return the relative file path.
     */
    private String relativePath(File file)
    {
        return file.getAbsolutePath().replace(root, "");
    }
}