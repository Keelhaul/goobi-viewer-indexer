/**
 * This file is part of the Goobi Solr Indexer - a content indexing tool for the Goobi viewer and OAI-PMH/SRU interfaces.
 *
 * Visit these websites for more information.
 *          - http://www.intranda.com
 *          - http://digiverso.com
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.intranda.digiverso.presentation.solr;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.intranda.digiverso.presentation.solr.helper.Configuration;
import de.intranda.digiverso.presentation.solr.helper.Hotfolder;
import de.intranda.digiverso.presentation.solr.helper.JDomXP;
import de.intranda.digiverso.presentation.solr.helper.MetadataHelper;
import de.intranda.digiverso.presentation.solr.helper.SolrHelper;
import de.intranda.digiverso.presentation.solr.helper.TextHelper;
import de.intranda.digiverso.presentation.solr.model.DataRepository;
import de.intranda.digiverso.presentation.solr.model.FatalIndexerException;
import de.intranda.digiverso.presentation.solr.model.IndexObject;
import de.intranda.digiverso.presentation.solr.model.IndexerException;
import de.intranda.digiverso.presentation.solr.model.LuceneField;
import de.intranda.digiverso.presentation.solr.model.SolrConstants;
import de.intranda.digiverso.presentation.solr.model.SolrConstants.DocType;
import de.intranda.digiverso.presentation.solr.model.config.MetadataConfigurationManager;
import de.intranda.digiverso.presentation.solr.model.writestrategy.ISolrWriteStrategy;
import de.intranda.digiverso.presentation.solr.model.writestrategy.LazySolrWriteStrategy;
import de.intranda.digiverso.presentation.solr.model.writestrategy.SerializingSolrWriteStrategy;

public class LidoIndexer extends AbstractIndexer {

    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(LidoIndexer.class);

    //    private List<SolrInputDocument> pages = new ArrayList<>();
    private List<SolrInputDocument> events = new ArrayList<>();

    //    private List<SolrInputDocument> docsToAdd = new ArrayList<>();
    private String imgFileNames = "";

    /**
     * Constructor.
     * 
     * @param hotfolder
     * @should set attributes correctly
     */
    public LidoIndexer(Hotfolder hotfolder) {
        this.hotfolder = hotfolder;
    }

    /**
     * Indexes a LIDO file.
     * 
     * @param doc
     * @param pyramidTiffFolder
     * @param mixFolder
     * @param writeStrategy
     * @param pageCountStart
     * @return
     * @should index record correctly
     * @should update record correctly
     */
    public String[] index(Document doc, Map<String, Path> dataFolders, ISolrWriteStrategy writeStrategy, int pageCountStart) {
        String[] ret = { "ERROR", null };
        String pi = null;
        try {
            this.xp = new JDomXP(doc);
            if (this.xp == null) {
                throw new IndexerException("Could not create XML parser.");
            }

            IndexObject indexObj = new IndexObject(getNextIddoc(hotfolder.getSolrHelper()));
            logger.debug("IDDOC: {}", indexObj.getIddoc());
            Element structNode = doc.getRootElement();
            indexObj.setRootStructNode(structNode);

            // set some simple data in den indexObject
            setSimpleData(indexObj);

            // Set PI
            {
                String preQuery = "/lido:lido/";
                pi = MetadataHelper.getPIFromXML(preQuery, xp);
                if (StringUtils.isNotBlank(pi)) {
                    // Remove prefix
                    if (pi.contains(":")) {
                        pi = pi.substring(pi.indexOf(':') + 1);
                    }
                    pi = MetadataHelper.applyIdentifierModifications(pi);
                    // Do not allow identifiers with illegal characters
                    Pattern p = Pattern.compile("[^\\w|-]");
                    Matcher m = p.matcher(pi);
                    if (m.find()) {
                        ret[1] = "PI contains illegal characters: " + pi;
                        throw new IndexerException(ret[1]);
                    }
                    indexObj.setPi(pi);
                    indexObj.setTopstructPI(pi);
                    logger.debug("PI: {}", indexObj.getPi());

                    // Determine the data repository to use
                    hotfolder.selectDataRepository(null, pi);
                    if (StringUtils.isNotEmpty(hotfolder.getSelectedRepository().getName())) {
                        indexObj.setDataRepository(hotfolder.getSelectedRepository().getName());
                    }

                    ret[0] = indexObj.getPi();

                    if (dataFolders.get(DataRepository.PARAM_TILEDIMAGES) == null) {
                        // Use the pyramid TIFF text folder
                        dataFolders.put(DataRepository.PARAM_TILEDIMAGES, Paths.get(hotfolder.getDataRepository().getDir(
                                DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), pi));
                        if (!Files.isDirectory(dataFolders.get(DataRepository.PARAM_TILEDIMAGES))) {
                            dataFolders.put(DataRepository.PARAM_TILEDIMAGES, null);
                        } else {
                            logger.info("Using old pyramid TIFF folder '{}'.", dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath());
                        }
                    }
                    if (dataFolders.get(DataRepository.PARAM_MIX) == null) {
                        // Use the old MIX folder
                        dataFolders.put(DataRepository.PARAM_MIX, Paths.get(hotfolder.getDataRepository().getDir(DataRepository.PARAM_MIX)
                                .toAbsolutePath().toString(), pi));
                        if (!Files.isDirectory(dataFolders.get(DataRepository.PARAM_MIX))) {
                            dataFolders.put(DataRepository.PARAM_MIX, null);
                        } else {
                            logger.info("Using old MIX folder '{}'.", dataFolders.get(DataRepository.PARAM_MIX).toAbsolutePath());
                        }
                    }
                } else {
                    ret[1] = "PI not found.";
                    throw new IndexerException(ret[1]);
                }
            }

            if (writeStrategy == null) {
                boolean useSerializingStrategy = false;
                if (useSerializingStrategy) {
                    writeStrategy = new SerializingSolrWriteStrategy(hotfolder.getSolrHelper(), hotfolder.getTempFolder());

                }
                //                else if (IndexerConfig.getInstance().getBoolean("init.aggregateRecords")) {
                //                    writeStrategy = new HierarchicalLazySolrWriteStrategy(hotfolder.getSolrHelper());
                //                }
                else {
                    writeStrategy = new LazySolrWriteStrategy(hotfolder.getSolrHelper());
                }
            } else {
                logger.info("Solr write strategy injected by caller: {}", writeStrategy.getClass().getName());
            }

            // Set partner ID
            if (Boolean.valueOf(Configuration.getInstance().getConfiguration("piContainsPartnerId"))) {
                String lidoRecID = xp.evaluateToString("/lido:lido/lido:lidoRecID[@lido:type='local']/text()", structNode);
                if (StringUtils.isNotEmpty(lidoRecID)) {
                    String[] lidoRecIDSplit = lidoRecID.split("/");
                    if (lidoRecIDSplit.length > 0) {
                        String partnerId = lidoRecIDSplit[0];
                        if (partnerId.contains(":")) {
                            partnerId = partnerId.substring(0, partnerId.indexOf(':'));
                        }
                        indexObj.setPartnerId(partnerId);
                        logger.debug("partnerId: {}", partnerId);
                    }
                }
            }

            // Set source doc format
            indexObj.addToLucene(SolrConstants.SOURCEDOCFORMAT, SolrConstants._LIDO);

            prepareUpdate(indexObj);
            indexObj.pushSimpleDataToLuceneArray();
            MetadataHelper.writeMetadataToObject(indexObj, indexObj.getRootStructNode(), "", xp);

            // If this is a volume (= has an anchor) that has already been indexed, copy access conditions from the anchor element
            if (indexObj.isVolume()) {
                String anchorPi = MetadataHelper.getAnchorPi(xp);
                if (anchorPi != null) {
                    SolrDocumentList hits = hotfolder.getSolrHelper().search(SolrConstants.PI + ":" + anchorPi, Collections.singletonList(
                            SolrConstants.ACCESSCONDITION));
                    if (hits != null && hits.getNumFound() > 0) {
                        Collection<Object> fields = hits.get(0).getFieldValues(SolrConstants.ACCESSCONDITION);
                        for (Object o : fields) {
                            indexObj.getAccessConditions().add(o.toString());
                        }
                    }
                }
            }

            // Add LABEL value
            if (StringUtils.isEmpty(indexObj.getLabel())) {
                LuceneField field = indexObj.getLuceneFieldWithName("MD_TITLE");
                if (field != null) {
                    indexObj.addToLucene(SolrConstants.LABEL, MetadataHelper.applyValueDefaultModifications(field.getValue()));
                }
            }

            generatePageDocuments(writeStrategy, dataFolders, pageCountStart);

            // Set access conditions
            indexObj.writeAccessConditions(null);

            // Add THUMBNAIL,THUMBPAGENO,THUMBPAGENOLABEL (must be done AFTER writeDateMondified(), writeAccessConditions() and generatePageDocuments()!)
            List<LuceneField> thumbnailFields = mapPagesToDocstruct(indexObj, true, writeStrategy, dataFolders, 0);
            if (thumbnailFields != null) {
                indexObj.getLuceneFields().addAll(thumbnailFields);
            }

            // ISWORK only for non-anchors
            indexObj.addToLucene(SolrConstants.ISWORK, "true");
            logger.trace("ISWORK: {}", indexObj.getLuceneFieldWithName(SolrConstants.ISWORK).getValue());

            // Write number of pages
            indexObj.addToLucene(SolrConstants.NUMPAGES, String.valueOf(writeStrategy.getPageDocsSize()));

            // Write created/updated timestamps
            indexObj.writeDateModified(!noTimestampUpdate);

            // Generate event documents (must happen before writing the DEFAULT field!)
            this.events = generateEvents(indexObj);
            logger.debug("Generated {} event docs.", events.size());

            // Add DEFAULT field
            if (StringUtils.isNotEmpty(indexObj.getDefaultValue())) {
                indexObj.addToLucene(SolrConstants.DEFAULT, cleanUpDefaultField(indexObj.getDefaultValue()));
                // indexObj.getSuperDefaultBuilder().append(' ').append(indexObj.getDefaultValue().trim());
                indexObj.setDefaultValue("");
            }

            StringBuilder sbSuperDefault = new StringBuilder();
            StringBuilder sbSuperFulltext = new StringBuilder();

            // Add event docs to the main list
            writeStrategy.addDocs(events);

            // Add aggregated metadata groups as separate documents
            for (List<LuceneField> metadataFieldList : indexObj.getGroupedMetadataFields()) {
                SolrInputDocument mdDoc = SolrHelper.createDocument(metadataFieldList);
                long iddoc = getNextIddoc(hotfolder.getSolrHelper());
                mdDoc.addField(SolrConstants.IDDOC, iddoc);
                if (!mdDoc.getFieldNames().contains(SolrConstants.GROUPFIELD)) {
                    logger.warn("{} not set in grouped metadata doc {}, using IDDOC instead.", SolrConstants.GROUPFIELD, mdDoc.getFieldValue(
                            SolrConstants.LABEL));
                    mdDoc.addField(SolrConstants.GROUPFIELD, iddoc);
                }
                mdDoc.addField(SolrConstants.IDDOC_OWNER, indexObj.getIddoc());
                mdDoc.addField(SolrConstants.DOCTYPE, DocType.METADATA.name());
                mdDoc.addField(SolrConstants.PI_TOPSTRUCT, indexObj.getPi());
                writeStrategy.addDoc(mdDoc);
            }

            // Add root doc
            SolrInputDocument rootDoc = SolrHelper.createDocument(indexObj.getLuceneFields());
            writeStrategy.setRootDoc(rootDoc);

            // WRITE TO SOLR (POINT OF NO RETURN: any indexObj modifications from here on will not be included in the index!)
            logger.debug("Writing document to index...");
            writeStrategy.writeDocs(Configuration.getInstance().isAggregateRecords());

            // Return image file names
            if (StringUtils.isNotEmpty(imgFileNames) && imgFileNames.charAt(0) == ';') {
                imgFileNames = imgFileNames.substring(1);
            }
            ret[1] = imgFileNames;
            logger.info("Successfully finished indexing '{}'.", pi);
        } catch (Exception e) {
            if ("No image resource sets found.".equals(e.getMessage())) {
                logger.error("Indexing of '{}' could not be finished due to an error: {}", pi, e.getMessage());
            } else {
                logger.error("Indexing of '{}' could not be finished due to an error.", pi);
                logger.error(e.getMessage(), e);
            }
            ret[0] = "ERROR";
            ret[1] = e.getMessage();
            hotfolder.getSolrHelper().rollback();
        } finally {
            if (writeStrategy != null) {
                writeStrategy.cleanup();
            }
        }

        return ret;
    }

    /**
     * 
     * @param indexObj
     * @param isWork
     * @param writeStrategy
     * @param dataFolders
     * @param depth
     * @return
     * @throws FatalIndexerException
     */
    private List<LuceneField> mapPagesToDocstruct(IndexObject indexObj, boolean isWork, ISolrWriteStrategy writeStrategy,
            Map<String, Path> dataFolders, int depth) throws FatalIndexerException {
        List<LuceneField> ret = new ArrayList<>();

        List<SolrInputDocument> pageDocs = writeStrategy.getPageDocsForPhysIdList(Collections.singletonList("LIDO"));
        if (!pageDocs.isEmpty()) {
            // If this is a top struct element, look for a representative image
            String filePathBanner = null;
            boolean thumbnailSet = false;
            SolrInputDocument firstPageDoc = pageDocs.get(0);
            int firstPageOrder = (int) firstPageDoc.getFieldValue(SolrConstants.ORDER);
            int offset = writeStrategy.getPageOrderOffset();
            ret.add(new LuceneField(SolrConstants.THUMBPAGENO, String.valueOf(firstPageOrder - offset)));
            ret.add(new LuceneField(SolrConstants.THUMBPAGENOLABEL, (String) firstPageDoc.getFieldValue(SolrConstants.ORDERLABEL)));
            if (StringUtils.isEmpty(filePathBanner)) {
                // Add thumbnail information from the first page
                String thumbnailFileName = firstPageDoc.getField(SolrConstants.FILENAME + "_HTML-SANDBOXED") != null ? (String) firstPageDoc
                        .getFieldValue(SolrConstants.FILENAME + "_HTML-SANDBOXED") : (String) firstPageDoc.getFieldValue(SolrConstants.FILENAME);
                ret.add(new LuceneField(SolrConstants.THUMBNAIL, thumbnailFileName));
                ret.add(new LuceneField(SolrConstants.MIMETYPE, (String) firstPageDoc.getFieldValue(SolrConstants.MIMETYPE)));
                thumbnailSet = true;
            }
            for (SolrInputDocument pageDoc : pageDocs) {
                String pageFileName = pageDoc.getField(SolrConstants.FILENAME + "_HTML-SANDBOXED") != null ? (String) pageDoc.getFieldValue(
                        SolrConstants.FILENAME + "_HTML-SANDBOXED") : (String) pageDoc.getFieldValue(SolrConstants.FILENAME);
                String pageFileBaseName = FilenameUtils.getBaseName(pageFileName);
                // Make sure IDDOC_OWNER of a page contains the iddoc of the lowest possible mapped docstruct
                if (pageDoc.getField("MDNUM_OWNERDEPTH") == null || depth > (Integer) pageDoc.getFieldValue("MDNUM_OWNERDEPTH")) {
                    pageDoc.setField(SolrConstants.IDDOC_OWNER, String.valueOf(indexObj.getIddoc()));
                    pageDoc.setField("MDNUM_OWNERDEPTH", depth);

                    // Add the parent document's structure element to the page
                    pageDoc.setField(SolrConstants.DOCSTRCT, indexObj.getType());

                    // Remove SORT_ fields from a previous, higher up docstruct
                    Set<String> fieldsToRemove = new HashSet<>();
                    for (String fieldName : pageDoc.getFieldNames()) {
                        if (fieldName.startsWith(SolrConstants.SORT_)) {
                            fieldsToRemove.add(fieldName);
                        }
                    }
                    for (String fieldName : fieldsToRemove) {
                        pageDoc.removeField(fieldName);
                    }
                    //  Add this docstruct's SORT_* fields to page
                    if (indexObj.getIddoc() == Long.valueOf((String) pageDoc.getFieldValue(SolrConstants.IDDOC_OWNER))) {
                        for (LuceneField field : indexObj.getLuceneFields()) {
                            if (field.getField().startsWith(SolrConstants.SORT_)) {
                                pageDoc.addField(field.getField(), field.getValue());
                            }
                        }
                    }
                }

                if (pageDoc.getField(SolrConstants.PI_TOPSTRUCT) == null) {
                    pageDoc.addField(SolrConstants.PI_TOPSTRUCT, indexObj.getTopstructPI());
                }
                if (pageDoc.getField(SolrConstants.DATAREPOSITORY) == null && indexObj.getDataRepository() != null) {
                    pageDoc.addField(SolrConstants.DATAREPOSITORY, indexObj.getDataRepository());
                }
                if (pageDoc.getField(SolrConstants.DATEUPDATED) == null && !indexObj.getDateUpdated().isEmpty()) {
                    for (Long date : indexObj.getDateUpdated()) {
                        pageDoc.addField(SolrConstants.DATEUPDATED, date);
                    }
                }

                // Add of each docstruct access conditions (no duplicates)
                Set<String> existingAccessConditions = new HashSet<>();
                if (pageDoc.getFieldValues(SolrConstants.ACCESSCONDITION) != null) {
                    for (Object obj : pageDoc.getFieldValues(SolrConstants.ACCESSCONDITION)) {
                        existingAccessConditions.add((String) obj);
                    }
                }
                for (String s : indexObj.getAccessConditions()) {
                    if (!existingAccessConditions.contains(s)) {
                        pageDoc.addField(SolrConstants.ACCESSCONDITION, s);
                    }
                }
                if (indexObj.getAccessConditions().isEmpty()) {
                    logger.warn("{}: {} has no access conditions.", pageFileBaseName, indexObj.getIddoc());
                }

                // Add owner docstruct's metadata (tokenized only!) and SORT_* fields to the page
                Set<String> existingMetadataFieldNames = new HashSet<>();
                Set<String> existingSortFieldNames = new HashSet<>();
                for (String fieldName : pageDoc.getFieldNames()) {
                    if (SolrIndexerDaemon.getInstance().getMetadataConfigurationManager().getFieldsToAddToPages().contains(fieldName)) {
                        for (Object value : pageDoc.getFieldValues(fieldName)) {
                            existingMetadataFieldNames.add(new StringBuilder(fieldName).append(String.valueOf(value)).toString());
                        }
                    } else if (fieldName.startsWith(SolrConstants.SORT_)) {
                        existingSortFieldNames.add(fieldName);
                    }
                }
                for (LuceneField field : indexObj.getLuceneFields()) {
                    if (SolrIndexerDaemon.getInstance().getMetadataConfigurationManager().getFieldsToAddToPages().contains(field.getField())
                            && !existingMetadataFieldNames.contains(new StringBuilder(field.getField()).append(field.getValue()).toString())) {
                        // Avoid duplicates (same field name + value)
                        pageDoc.addField(field.getField(), field.getValue());
                        logger.debug("Added {}:{} to page {}", field.getField(), field.getValue(), pageDoc.getFieldValue(SolrConstants.ORDER));
                    } else if (field.getField().startsWith(SolrConstants.SORT_) && !existingSortFieldNames.contains(field.getField())) {
                        // Only one instance of each SORT_ field may exist
                        pageDoc.addField(field.getField(), field.getValue());
                    }
                }

                // Add used-generated content docs
                if (dataFolders.get(DataRepository.PARAM_UGC) != null && pageDoc.getField(SolrConstants.UGCTERMS) == null) {
                    writeStrategy.addDocs(generateUserGeneratedContentDocsForPage(pageDoc, dataFolders.get(DataRepository.PARAM_UGC), String.valueOf(
                            indexObj.getParentPI()), (Integer) pageDoc.getFieldValue(SolrConstants.ORDER), pageFileBaseName));
                }

                // Update the doc in the write strategy (otherwise some implementations might ignore the changes).
                writeStrategy.updateDoc(pageDoc);
            }

            // If a representative image is set but not mapped to any docstructs, do not use it
            if (!thumbnailSet && StringUtils.isNotEmpty(filePathBanner) && !pageDocs.isEmpty()) {
                logger.warn("Selected representative image '{}' is not mapped to any structure element - using first mapped image instead.",
                        filePathBanner);
                String pageFileName = pageDocs.get(0).getField(SolrConstants.FILENAME + "_HTML-SANDBOXED") != null ? (String) pageDocs.get(0)
                        .getFieldValue(SolrConstants.FILENAME + "_HTML-SANDBOXED") : (String) pageDocs.get(0).getFieldValue(SolrConstants.FILENAME);
                ret.add(new LuceneField(SolrConstants.THUMBNAIL, pageFileName));
                // THUMBNAILREPRESENT is just used to identify the presence of a custom representation thumbnail to the indexer, it is not used in the viewer
                ret.add(new LuceneField(SolrConstants.THUMBNAILREPRESENT, pageFileName));
                ret.add(new LuceneField(SolrConstants.MIMETYPE, (String) pageDocs.get(0).getFieldValue(SolrConstants.MIMETYPE)));
                thumbnailSet = true;
            }

            // Add the number of assigned pages and the labels of the first and last page to this structure element
            indexObj.setNumPages(pageDocs.size());
            if (!pageDocs.isEmpty()) {
                SolrInputDocument lastPagedoc = pageDocs.get(pageDocs.size() - 1);
                String firstPageLabel = (String) firstPageDoc.getFieldValue(SolrConstants.ORDERLABEL);
                String lastPageLabel = (String) lastPagedoc.getFieldValue(SolrConstants.ORDERLABEL);
                if (firstPageLabel != null && !"-".equals(firstPageLabel.trim())) {
                    indexObj.setFirstPageLabel(firstPageLabel);
                }
                if (lastPageLabel != null && !"-".equals(lastPageLabel.trim())) {
                    indexObj.setLastPageLabel(lastPageLabel);
                }
                // logger.info(indexObj.getLogId() + ": " + indexObj.getFirstPageLabel() + " - " + indexObj.getLastPageLabel());
            }
        } else {
            logger.warn("No pages found for {}", indexObj.getLogId());
        }

        return ret;
    }

    /**
     * 
     * @param writeStrategy
     * @param pyramidTiffFolder
     * @param mixFolder
     * @param pageCountStart
     * @throws FatalIndexerException
     */
    public void generatePageDocuments(ISolrWriteStrategy writeStrategy, Map<String, Path> dataFolders, int pageCountStart)
            throws FatalIndexerException {
        String xpath = "/lido:lido/lido:administrativeMetadata/lido:resourceWrap/lido:resourceSet/lido:resourceID";
        List<Element> resourceSetList = xp.evaluateToElements(xpath, null);
        if (resourceSetList == null || resourceSetList.isEmpty()) {
            xpath = "/lido:lido/lido:administrativeMetadata/lido:resourceWrap/lido:resourceSet/lido:resourceRepresentation[@lido:type='image_overview']/lido:linkResource";
            resourceSetList = xp.evaluateToElements(xpath, null);
        }
        if (resourceSetList == null || resourceSetList.isEmpty()) {
            return;
        }

        logger.info("Generating {} page documents (count starts at {})...", resourceSetList.size(), pageCountStart);

        // TODO lambda instead of loop (find a way to preserve order first)
        //        resourceSetList.parallelStream().forEach(
        //                eleResourceSet -> generatePageDocument(eleResourceSet, String.valueOf(getNextIddoc(hotfolder.getSolrHelper())), null,
        //                        writeStrategy, dataFolders));
        int order = pageCountStart;
        for (Element eleResourceSet : resourceSetList) {
            if (generatePageDocument(eleResourceSet, String.valueOf(getNextIddoc(hotfolder.getSolrHelper())), order, writeStrategy, dataFolders)) {
                order++;
            }
        }

        logger.info("Generated {} page documents.", writeStrategy.getPageDocsSize());
    }

    /**
     * 
     * @param eleResourceSet
     * @param iddoc
     * @param order
     * @param writeStrategy
     * @param pyramidTiffFolder
     * @param mixFolder
     * @return
     */
    boolean generatePageDocument(Element eleResourceSet, String iddoc, Integer order, ISolrWriteStrategy writeStrategy,
            Map<String, Path> dataFolders) {
        if (order == null) {
            // TODO parallel processing of pages will required Goobi to put values starting with 1 into the ORDER attribute
        }

        // Create Solr document for this page
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(SolrConstants.IDDOC, iddoc);
        doc.addField(SolrConstants.GROUPFIELD, iddoc);
        doc.addField(SolrConstants.DOCTYPE, DocType.PAGE.name());
        doc.addField(SolrConstants.ORDER, order);

        String orderLabel = eleResourceSet.getAttributeValue("ORDERLABEL");
        if (StringUtils.isNotEmpty(orderLabel)) {
            doc.addField(SolrConstants.ORDERLABEL, orderLabel);
        } else {
            doc.addField(SolrConstants.ORDERLABEL, " - ");
        }

        String fileName = eleResourceSet.getTextTrim();
        if (StringUtils.isNotEmpty(fileName)) {
            doc.addField(SolrConstants.FILENAME, fileName);
            String mimetype = "image"; // TODO other types?
            doc.addField(SolrConstants.MIMETYPE, mimetype);
        }
        // Add file size
        if (dataFolders != null) {
            try {
                Path dataFolder = dataFolders.get(DataRepository.PARAM_MEDIA);
                // TODO other mime types/folders
                if (dataFolder != null) {
                    Path path = Paths.get(dataFolder.toAbsolutePath().toString(), fileName);
                    doc.addField("MDNUM_FILESIZE", Files.size(path));
                } else {
                    doc.addField("MDNUM_FILESIZE", -1);
                }
            } catch (IllegalArgumentException | IOException e) {
                logger.warn(e.getMessage());
                doc.addField("MDNUM_FILESIZE", -1);
            }
        }

        String baseFileName = FilenameUtils.getBaseName((String) doc.getFieldValue(SolrConstants.FILENAME));

        // Check for tiled images
        if (dataFolders.get(DataRepository.PARAM_TILEDIMAGES) != null && Files.isDirectory(dataFolders.get(DataRepository.PARAM_TILEDIMAGES))) {
            Path rotated0PyramidTiff = Paths.get(dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), baseFileName
                    + "_0degree.tif");
            if (!Files.exists(rotated0PyramidTiff)) {
                rotated0PyramidTiff = Paths.get(dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), baseFileName
                        + "_0degree.jp2");
            }
            if (Files.isRegularFile(rotated0PyramidTiff)) {
                doc.addField(SolrConstants.FILENAME_TILED_0, rotated0PyramidTiff.getFileName().toString());
                logger.debug("Found 0° tiled image: {}", rotated0PyramidTiff.toAbsolutePath());
                Path rotated90PyramidTiff = Paths.get(dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), baseFileName
                        + "_90degree.tif");
                if (!Files.exists(rotated90PyramidTiff)) {
                    rotated90PyramidTiff = Paths.get(dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), baseFileName
                            + "_90degree.jp2");
                }
                Path rotated180PyramidTiff = Paths.get(dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), baseFileName
                        + "_180degree.tif");
                if (!Files.exists(rotated180PyramidTiff)) {
                    rotated180PyramidTiff = Paths.get(dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), baseFileName
                            + "_180degree.jp2");
                }
                Path rotated270PyramidTiff = Paths.get(dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), baseFileName
                        + "_270degree.tif");
                if (!Files.exists(rotated270PyramidTiff)) {
                    rotated270PyramidTiff = Paths.get(dataFolders.get(DataRepository.PARAM_TILEDIMAGES).toAbsolutePath().toString(), baseFileName
                            + "_270degree.jp2");
                }
                if (Files.isRegularFile(rotated90PyramidTiff) && Files.isRegularFile(rotated180PyramidTiff) && Files.isRegularFile(
                        rotated270PyramidTiff)) {
                    doc.addField(SolrConstants.FILENAME_TILED_90, rotated90PyramidTiff.getFileName().toString());
                    doc.addField(SolrConstants.FILENAME_TILED_180, rotated180PyramidTiff.getFileName().toString());
                    doc.addField(SolrConstants.FILENAME_TILED_270, rotated270PyramidTiff.getFileName().toString());
                    logger.debug("Found rotated tiled images.");
                }
            }
        }
        if (dataFolders.get(DataRepository.PARAM_MIX) != null) {
            try {
                Map<String, String> mixData = TextHelper.readMix(new File(dataFolders.get(DataRepository.PARAM_MIX).toAbsolutePath().toString(),
                        baseFileName + AbstractIndexer.XML_EXTENSION));
                for (String key : mixData.keySet()) {
                    if (!(key.equals(SolrConstants.WIDTH) && doc.getField(SolrConstants.WIDTH) != null) && !(key.equals(SolrConstants.HEIGHT) && doc
                            .getField(SolrConstants.HEIGHT) != null)) {
                        doc.addField(key, mixData.get(key));
                    }
                }
            } catch (JDOMException e) {
                logger.error(e.getMessage(), e);
            } catch (IOException e) {
                logger.warn(e.getMessage());
            }

        }

        writeStrategy.addPageDoc(doc);
        return true;
    }

    /**
     * @param indexObj IndexObject of the parent docstruct (usually the top level docstruct).
     * @param mh MetadataHelper instance.
     * @return
     * @throws FatalIndexerException
     */
    private List<SolrInputDocument> generateEvents(IndexObject indexObj) throws FatalIndexerException {
        List<SolrInputDocument> ret = new ArrayList<>();

        String query = "/lido:lido/lido:descriptiveMetadata/lido:eventWrap/lido:eventSet/lido:event";
        List<Element> eventList = xp.evaluateToElements(query, null);
        if (eventList != null && !eventList.isEmpty()) {
            logger.info("Found {} event(s).", eventList.size());
            String defaultFieldBackup = indexObj.getDefaultValue();
            for (Element eleEvent : eventList) {
                SolrInputDocument eventDoc = new SolrInputDocument();
                long iddocEvent = getNextIddoc(hotfolder.getSolrHelper());
                eventDoc.addField(SolrConstants.IDDOC, iddocEvent);
                eventDoc.addField(SolrConstants.GROUPFIELD, iddocEvent);
                eventDoc.addField(SolrConstants.DOCTYPE, DocType.EVENT.name());
                List<LuceneField> dcFields = indexObj.getLuceneFieldsWithName(SolrConstants.DC);
                if (dcFields != null) {
                    for (LuceneField field : dcFields) {
                        eventDoc.addField(field.getField(), field.getValue());
                    }
                }
                eventDoc.setField(SolrConstants.IDDOC_OWNER, String.valueOf(indexObj.getIddoc()));
                eventDoc.addField(SolrConstants.PI_TOPSTRUCT, indexObj.getPi());

                // Find event type
                query = "lido:eventType/lido:term/text()";
                String type = xp.evaluateToString(query, eleEvent);
                if (StringUtils.isNotBlank(type)) {
                    eventDoc.addField(SolrConstants.EVENTTYPE, type);
                    indexObj.setDefaultValue(type);
                } else {
                    logger.error("Event type not found.");
                }

                // Create a backup of the current grouped metadata list of the parent docstruct
                List<List<LuceneField>> groupedFieldsBackup = new ArrayList<>(indexObj.getGroupedMetadataFields());
                List<LuceneField> fields = MetadataHelper.retrieveElementMetadata(eleEvent, "", indexObj, xp);

                // Add aggregated metadata groups as separate documents
                if (indexObj.getGroupedMetadataFields().size() > groupedFieldsBackup.size()) {
                    // Newly added items in IndexObject.groupedMetadataFields come from the event, so just use these new items
                    List<List<LuceneField>> eventGroupedFields = indexObj.getGroupedMetadataFields().subList(groupedFieldsBackup.size(), indexObj
                            .getGroupedMetadataFields().size());
                    for (List<LuceneField> metadataFieldList : eventGroupedFields) {
                        SolrInputDocument doc = SolrHelper.createDocument(metadataFieldList);
                        long iddoc = getNextIddoc(hotfolder.getSolrHelper());
                        doc.addField(SolrConstants.IDDOC, iddoc);
                        if (!doc.getFieldNames().contains(SolrConstants.GROUPFIELD)) {
                            logger.warn("{} not set in grouped metadata doc {}, using IDDOC instead.", SolrConstants.GROUPFIELD, doc.getFieldValue(
                                    SolrConstants.LABEL));
                            doc.addField(SolrConstants.GROUPFIELD, iddoc);
                        }
                        // IDDOC_OWNER should always contain the IDDOC of the lowest docstruct to which this page is mapped. Since child docstructs are added recursively, this should be the case without further conditions.
                        doc.addField(SolrConstants.IDDOC_OWNER, iddocEvent);
                        doc.addField(SolrConstants.DOCTYPE, DocType.METADATA.name());
                        doc.addField(SolrConstants.PI_TOPSTRUCT, indexObj.getTopstructPI());

                        // Add DC values to metadata doc
                        if (dcFields != null) {
                            for (LuceneField field : dcFields) {
                                doc.addField(field.getField(), field.getValue());
                            }
                        }

                        ret.add(doc);
                    }

                    // Grouped metadata fields are written directly into the IndexObject, which is not desired. Replace the metadata from the backup.{
                    indexObj.setGroupedMetadataFields(groupedFieldsBackup);
                }

                for (LuceneField field : fields) {
                    eventDoc.addField(field.getField(), field.getValue());
                    logger.debug("Added {}:{} to event '{}'.", field.getField(), field.getValue(), type);
                }

                // Use the main IndexObject's default value field to collect default values for the events, then restore the original value
                if (StringUtils.isNotEmpty(indexObj.getDefaultValue())) {
                    eventDoc.addField(SolrConstants.DEFAULT, indexObj.getDefaultValue());
                    // indexObj.getSuperDefaultBuilder().append(' ').append(indexObj.getDefaultValue().trim());
                    indexObj.setDefaultValue(defaultFieldBackup);
                }

                ret.add(eventDoc);
                logger.debug(eventDoc.getFieldNames().toString());
            }
        }

        return ret;
    }

    /**
     * Prepares the given record for an update. Creation timestamp is preserved. A new update timestamp is added, child docs are removed.
     * 
     * @param indexObj {@link IndexObject}
     * @throws IOException -
     * @throws SolrServerException
     * @throws FatalIndexerException
     */
    private void prepareUpdate(IndexObject indexObj) throws IOException, SolrServerException, FatalIndexerException {
        String pi = indexObj.getPi().trim();
        SolrDocumentList hits = hotfolder.getSolrHelper().search(SolrConstants.PI + ":" + pi, null);
        if (hits != null && hits.getNumFound() > 0) {
            logger.debug("This file has already been indexed, initiating an UPDATE instead...");
            indexObj.setUpdate(true);
            SolrDocument doc = hits.get(0);
            // Set creation timestamp, if exists (should never be updated)
            Object dateCreated = doc.getFieldValue(SolrConstants.DATECREATED);
            if (dateCreated != null) {
                // Set creation timestamp, if exists (should never be updated)
                indexObj.setDateCreated((Long) dateCreated);
            }
            // Set update timestamp
            Collection<Object> dateUpdatedValues = doc.getFieldValues(SolrConstants.DATEUPDATED);
            if (dateUpdatedValues != null) {
                for (Object date : dateUpdatedValues) {
                    indexObj.getDateUpdated().add((Long) date);
                }
            }
            // Recursively delete all children
            deleteWithPI(pi, false, hotfolder.getSolrHelper());
        }
    }

    /**
     * Sets TYPE and LABEL from the LIDO document.
     * 
     * @param indexObj {@link IndexObject}
     * @throws FatalIndexerException
     */
    private void setSimpleData(IndexObject indexObj) throws FatalIndexerException {
        Element structNode = indexObj.getRootStructNode();

        // Set type
        List<String> values = xp.evaluateToStringList(
                "lido:descriptiveMetadata/lido:objectClassificationWrap/lido:objectWorkTypeWrap/lido:objectWorkType/lido:term/text()", structNode);
        if (values != null && !values.isEmpty()) {
            indexObj.setType(MetadataConfigurationManager.mapDocStrct((values.get(0)).trim()));
        }
        logger.trace("TYPE: {}", indexObj.getType());

        // Set label
        {
            String value = structNode.getAttributeValue("LABEL");
            if (value != null) {
                indexObj.setLabel(value);
            }
        }
        logger.trace("LABEL: {}", indexObj.getLabel());
    }

    public static FilenameFilter txt = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".txt");
        }
    };

    public static FilenameFilter xml = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".xml");
        }
    };
}
