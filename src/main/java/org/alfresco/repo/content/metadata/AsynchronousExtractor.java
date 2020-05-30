/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2020 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
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
 * #L%
 */
package org.alfresco.repo.content.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.action.executer.ContentMetadataExtracter;
import org.alfresco.repo.content.transform.TransformerDebug;
import org.alfresco.repo.rendition2.RenditionService2;
import org.alfresco.repo.rendition2.TransformDefinition;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.tagging.TaggingService;
import org.alfresco.service.namespace.NamespaceException;
import org.alfresco.service.namespace.NamespacePrefixResolver;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.transform.client.model.config.TransformConfig;
import org.alfresco.transform.client.registry.TransformServiceRegistry;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Requests an extract of metadata via a remote async transform using
 * {@link RenditionService2#transform(NodeRef, TransformDefinition)}. The properties that will extracted are defined
 * by the transform. This allows out of process metadata extracts to be defined without the need to apply an AMP.
 * The actual transform is a request to go from the source mimetype to a target mimetype that is the source mimetype
 * prefix by {@code "alfresco-metadata-extractor/"}. The resulting transform is a Map in json of properties and values
 * to be set on the source node.
 * <p>
 * As with other sub-classes of {@link AbstractMappingMetadataExtracter} it also supports embedding of metadata in
 * a source node. In this case the remote async transform states that it supports a transform from a source mimetype
 * to a target mimetype that is the source mimetype prefix by {@code "alfresco-metadata-embedder/"}. The resulting
 * transform is a replacement for the content of the node.
 *
 * @author adavis
 */
public class AsynchronousExtractor extends AbstractMappingMetadataExtracter
{
    private static final String EXTRACTOR = "extractor";
    private static final String EMBEDDER = "embedder";
    private static final String ALFRESCO_METADATA = "alfresco-metadata-";
    private static final char SLASH = '/';
    public static final String EXTRACTOR_MIMETYPE_PREFIX = ALFRESCO_METADATA + EXTRACTOR + SLASH;
    public static final String EMBEDDER_MIMETYPE_PREFIX = ALFRESCO_METADATA + EMBEDDER + SLASH;

    private final ObjectMapper jsonObjectMapper = new ObjectMapper();

    private NodeService nodeService;
    private NamespacePrefixResolver namespacePrefixResolver;
    private RenditionService2 renditionService2;
    private ContentService contentService;
    private TransactionService transactionService;
    private TransformServiceRegistry transformServiceRegistry;
    private TaggingService taggingService;

    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }

    public void setNamespacePrefixResolver(NamespacePrefixResolver namespacePrefixResolver)
    {
        this.namespacePrefixResolver = namespacePrefixResolver;
    }

    public void setRenditionService2(RenditionService2 renditionService2)
    {
        this.renditionService2 = renditionService2;
    }

    public void setContentService(ContentService contentService)
    {
        this.contentService = contentService;
    }

    public void setTransactionService(TransactionService transactionService)
    {
        this.transactionService = transactionService;
    }

    public void setTransformServiceRegistry(TransformServiceRegistry transformServiceRegistry)
    {
        this.transformServiceRegistry = transformServiceRegistry;
    }

    public void setTaggingService(TaggingService taggingService)
    {
        this.taggingService = taggingService;
    }

    @Override
    protected Map<String, Set<QName>> getDefaultMapping()
    {
        return Collections.emptyMap(); // Mappings are done by the transform
    }

    public boolean isSupported(String sourceMimetype, long sourceSizeInBytes)
    {
        return isEnabled(sourceMimetype) && isSupported(sourceMimetype, sourceSizeInBytes, EXTRACTOR_MIMETYPE_PREFIX);
    }

    public boolean isEmbedderSupported(String sourceMimetype, long sourceSizeInBytes)
    {
        return isSupported(sourceMimetype, sourceSizeInBytes, EMBEDDER_MIMETYPE_PREFIX);
    }

    private boolean isSupported(String sourceMimetype, long sourceSizeInBytes, String prefix)
    {
        String targetMimetype = prefix + sourceMimetype;
        return transformServiceRegistry.isSupported(sourceMimetype, sourceSizeInBytes, targetMimetype, Collections.emptyMap(), targetMimetype);
    }

    public static boolean isMetadataMimetype(String targetMimetype)
    {
        return isMetadataExtractMimetype(targetMimetype) || isMetadataEmbedderMimetype(targetMimetype);
    }

    public static boolean isMetadataExtractMimetype(String targetMimetype)
    {
        return targetMimetype.startsWith(EXTRACTOR_MIMETYPE_PREFIX);
    }

    public static boolean isMetadataEmbedderMimetype(String targetMimetype)
    {
        return targetMimetype.startsWith(EMBEDDER_MIMETYPE_PREFIX);
    }

    /**
     * Returns a file extension used as the target in a transform. The normal extension is changed if the
     * {@code targetMimetype} is an extraction or embedding type.
     *
     * @param targetMimetype the target mimetype
     * @param sourceExtension normal source extension
     * @param targetExtension current target extension (normally {@code "bin" in these cases})
     * @return the extension to be used.
     */
    public static String getExtension(String targetMimetype, String sourceExtension, String targetExtension)
    {
        return isMetadataExtractMimetype(targetMimetype)
                ? "alfx"
                : isMetadataEmbedderMimetype(targetMimetype)
                ? sourceExtension
                : targetExtension;
    }

    /**
     * Returns a rendition name used in {@link TransformerDebug}. The normal name is changed if it is an extraction or
     * embedding type. The name in this case is actually the target mimetype.
     *
     * @param renditionName normal name, but is the targetMimetype in the case of metadata extract and embed.
     * @return the renditionName to be used.
     */
    public static String getRenditionName(String renditionName)
    {
        return isMetadataExtractMimetype(renditionName)
                ? "metadata-extract"
                : isMetadataEmbedderMimetype(renditionName)
                ? "metadata-embed"
                : renditionName;
    }

    @Override
    protected void checkIsSupported(ContentReader reader)
    {
        // Just return, as we have already checked when this extractor was selected.
    }

    @Override
    protected void checkIsEmbedSupported(ContentWriter writer)
    {
        // Just return, as we have already checked when this embedder was selected.
    }

    @Override
    protected Map<String, Serializable> extractRaw(ContentReader reader) throws Throwable
    {
        throw new IllegalStateException("Overloaded method should have been called.");
    }

    @Override
    protected Map<String, Serializable> extractRaw(NodeRef nodeRef, ContentReader reader) throws Throwable
    {
        transform(nodeRef, reader, EXTRACTOR_MIMETYPE_PREFIX, EXTRACTOR, Collections.emptyMap());
        return Collections.emptyMap();
    }

    @Override
    protected void embedInternal(NodeRef nodeRef, Map<String, Serializable> metadata, ContentReader reader, ContentWriter writer)
    {
        // TODO pass metadata as a transform option
        Map<String, String> options = Collections.emptyMap();
         transform(nodeRef, reader, EMBEDDER_MIMETYPE_PREFIX, EMBEDDER, options);
    }

    private void transform(NodeRef nodeRef, ContentReader reader, String prefix, String action, Map<String, String> options)
    {
        String sourceMimetype = reader.getMimetype();
        String targetMimetype = prefix + sourceMimetype;

        TransformDefinition transformDefinition = new TransformDefinition(targetMimetype, targetMimetype,
                options, null, null, null);

        if (logger.isDebugEnabled())
        {
            StringJoiner sj = new StringJoiner("\n");
            sj.add("Request " + action + " transform on " + nodeRef);
            options.forEach((k,v)->sj.add("  "+k+"="+v));
            logger.debug(sj);
        }

        AuthenticationUtil.runAs(
                (AuthenticationUtil.RunAsWork<Void>) () ->
                transactionService.getRetryingTransactionHelper().doInTransaction(() ->
                {
                    renditionService2.transform(nodeRef, transformDefinition);
                    return null;
                }), AuthenticationUtil.getSystemUserName());
    }

    public void setMetadata(NodeRef nodeRef, InputStream transformInputStream)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Update metadata on " + nodeRef);
        }

        Map<String, Serializable> metadata = readMetadata(transformInputStream);
        if (metadata == null)
        {
            return; // Error state.
        }

        // Remove well know entries from the map that drive how the real metadata is applied.
        OverwritePolicy overwritePolicy = removeOverwritePolicy(metadata, "sys:overwritePolicy", OverwritePolicy.PRAGMATIC);
        Boolean enableStringTagging = removeBoolean(metadata, "sys:enableStringTagging", false);
        Boolean carryAspectProperties = removeBoolean(metadata, "sys:carryAspectProperties", true);
        List<String> stringTaggingSeparators = removeTaggingSeparators(metadata, "sys:stringTaggingSeparators",
                ContentMetadataExtracter.DEFAULT_STRING_TAGGING_SEPARATORS);
        if (overwritePolicy == null ||
            enableStringTagging == null ||
            carryAspectProperties == null ||
            stringTaggingSeparators == null)
        {
            return; // Error state.
        }

        AuthenticationUtil.runAsSystem((AuthenticationUtil.RunAsWork<Void>) () ->
                transactionService.getRetryingTransactionHelper().doInTransaction(() ->
                {
                    Map<QName, Serializable> properties = convertKeysToQNames(metadata);
                    properties = convertSystemPropertyValues(properties);

                    Map<QName, Serializable> nodeProperties = nodeService.getProperties(nodeRef);
                    Map<QName, Serializable> changedProperties =
                            overwritePolicy.applyProperties(properties, nodeProperties);

                    ContentMetadataExtracter.addExtractedMetadataToNode(nodeRef, changedProperties, nodeService,
                            dictionaryService, taggingService,
                            enableStringTagging, carryAspectProperties, stringTaggingSeparators);

                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Extraction of Metadata from " + nodeRef + " complete " + changedProperties);
                    }

                    return null;
                }, false, true));
    }

    private Map<String, Serializable> readMetadata(InputStream transformInputStream)
    {
        try
        {
            TypeReference<HashMap<String, Serializable>> typeRef = new TypeReference<HashMap<String, Serializable>>() {};
            return jsonObjectMapper.readValue(transformInputStream, typeRef);
        }
        catch (IOException e)
        {
            logger.error("Failed to read metadata from transform result", e);
            return null;
        }
    }

    private OverwritePolicy removeOverwritePolicy(Map<String, Serializable> map, String key, OverwritePolicy defaultValue)
    {
        Serializable value = map.remove(key);
        try
        {
            return OverwritePolicy.valueOf((String)value);
        }
        catch (IllegalArgumentException|ClassCastException e)
        {
            logger.error(key + "=" + value + " is invalid");
            return null;
        }
    }

    private Boolean removeBoolean(Map<String, Serializable> map, Serializable key, boolean defaultValue)
    {
        Serializable value = map.remove(key);
        if (!(value instanceof String) || !Boolean.FALSE.toString().equals(value) && !Boolean.TRUE.toString().equals(value))
        {
            logger.error(key + "=" + value + " is invalid. Must be " + Boolean.TRUE + " or " + Boolean.FALSE);
            return null; // no flexibility of parseBoolean(...). It is just invalid
        }
        return value == null ? defaultValue : Boolean.parseBoolean((String)value);
    }

    private List<String> removeTaggingSeparators(Map<String, Serializable> map, String key, List<String> defaultValue)
    {
        Serializable value = map.remove(key);
        if (value == null)
        {
            return defaultValue;
        }
        if (!(value instanceof String))
        {
            logger.error(key + "=" + value + " is invalid.");
            return null;
        }

        List<String> list = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse((String)value, CSVFormat.RFC4180))
        {
            Iterator<CSVRecord> iterator = parser.iterator();
            CSVRecord record = iterator.next();
            if (iterator.hasNext())
            {
                logger.error(key + "=" + value + " is invalid. Should only have one record");
                return null;
            }
            record.forEach(f->list.add(f));
        }
        catch (IOException|NoSuchElementException e)
        {
            logger.error(key + "=" + value + " is invalid. Must be a CSV using CSVFormat.RFC4180");
            return null;
        }
        return list;
    }

    private Map<QName, Serializable> convertKeysToQNames(Map<String, Serializable> documentMetadata)
    {
        Map<QName, Serializable> properties = new HashMap<>();
        for (Map.Entry<String, Serializable> entry : documentMetadata.entrySet())
        {
            String key = entry.getKey();
            Serializable value = entry.getValue();
            try
            {
                QName qName = QName.createQName(key, namespacePrefixResolver);
                properties.put(qName, value);
            }
            catch (NamespaceException e)
            {
                logger.error("Error creating qName from "+key);
                return Collections.emptyMap();
            }
        }
        return properties;
    }

    public void setEmbeddedMetadata(NodeRef nodeRef, InputStream transformInputStream)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Update of content to include metadata on " + nodeRef);
        }
        AuthenticationUtil.runAsSystem((AuthenticationUtil.RunAsWork<Void>) () ->
                transactionService.getRetryingTransactionHelper().doInTransaction(() ->
                {
                    try
                    {
                        // Set or replace content
                        ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
                        String mimetype = reader.getMimetype();
                        String encoding = reader.getEncoding();
                        ContentWriter writer = contentService.getWriter(nodeRef, ContentModel.PROP_CONTENT, true);
                        writer.setMimetype(mimetype);
                        writer.setEncoding(encoding);
                        writer.putContent(transformInputStream);

                        if (logger.isDebugEnabled())
                        {
                            logger.debug("Embedded Metadata on " + nodeRef + " complete");
                        }
                    }
                    catch (Exception e)
                    {
                        logger.error("Failed to copy embedded metadata transform InputStream into " + nodeRef);
                        throw e;
                    }

                    return null;
                }, false, true));
    }
}