/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.metadata.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.asterix.common.config.DatasetConfig.DatasetType;
import org.apache.asterix.common.config.DatasetConfig.ExternalFilePendingOp;
import org.apache.asterix.common.config.DatasetConfig.TransactionState;
import org.apache.asterix.common.context.IStorageComponentProvider;
import org.apache.asterix.common.transactions.IResourceFactory;
import org.apache.asterix.external.api.IAdapterFactory;
import org.apache.asterix.external.indexing.ExternalFile;
import org.apache.asterix.external.indexing.FilesIndexDescription;
import org.apache.asterix.external.indexing.IndexingConstants;
import org.apache.asterix.external.operators.ExternalDatasetIndexesAbortOperatorDescriptor;
import org.apache.asterix.external.operators.ExternalDatasetIndexesCommitOperatorDescriptor;
import org.apache.asterix.external.operators.ExternalDatasetIndexesRecoverOperatorDescriptor;
import org.apache.asterix.external.operators.ExternalFilesIndexOperatorDescriptor;
import org.apache.asterix.external.operators.ExternalScanOperatorDescriptor;
import org.apache.asterix.external.operators.IndexInfoOperatorDescriptor;
import org.apache.asterix.external.provider.AdapterFactoryProvider;
import org.apache.asterix.external.util.ExternalDataConstants;
import org.apache.asterix.metadata.MetadataManager;
import org.apache.asterix.metadata.declared.BTreeDataflowHelperFactoryProvider;
import org.apache.asterix.metadata.declared.MetadataProvider;
import org.apache.asterix.metadata.entities.Dataset;
import org.apache.asterix.metadata.entities.ExternalDatasetDetails;
import org.apache.asterix.metadata.entities.Index;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.runtime.utils.RuntimeComponentsProvider;
import org.apache.asterix.runtime.utils.RuntimeUtils;
import org.apache.asterix.transaction.management.resource.ExternalBTreeLocalResourceMetadataFactory;
import org.apache.asterix.transaction.management.resource.PersistentLocalResourceFactoryProvider;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import org.apache.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraintHelper;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.core.jobgen.impl.ConnectorPolicyAssignmentPolicy;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobSpecification;
import org.apache.hyracks.dataflow.std.file.IFileSplitProvider;
import org.apache.hyracks.storage.am.common.dataflow.IIndexDataflowHelperFactory;
import org.apache.hyracks.storage.am.common.dataflow.IndexDropOperatorDescriptor;
import org.apache.hyracks.storage.am.common.impls.NoOpOperationCallbackFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMMergePolicyFactory;
import org.apache.hyracks.storage.am.lsm.common.dataflow.LSMTreeIndexCompactOperatorDescriptor;
import org.apache.hyracks.storage.common.file.LocalResource;

public class ExternalIndexingOperations {
    private static final Logger LOGGER = Logger.getLogger(ExternalIndexingOperations.class.getName());
    public static final List<List<String>> FILE_INDEX_FIELD_NAMES =
            Collections.unmodifiableList(Collections.singletonList(Collections.singletonList("")));
    public static final List<IAType> FILE_INDEX_FIELD_TYPES =
            Collections.unmodifiableList(Collections.singletonList(BuiltinType.ASTRING));

    private ExternalIndexingOperations() {
    }

    public static boolean isIndexible(ExternalDatasetDetails ds) {
        String adapter = ds.getAdapter();
        if (adapter.equalsIgnoreCase(ExternalDataConstants.ALIAS_HDFS_ADAPTER)) {
            return true;
        }
        return false;
    }

    public static boolean isRefereshActive(ExternalDatasetDetails ds) {
        return ds.getState() != TransactionState.COMMIT;
    }

    public static boolean isValidIndexName(String datasetName, String indexName) {
        return !datasetName.concat(IndexingConstants.EXTERNAL_FILE_INDEX_NAME_SUFFIX).equals(indexName);
    }

    public static int getRIDSize(Dataset dataset) {
        ExternalDatasetDetails dsd = (ExternalDatasetDetails) dataset.getDatasetDetails();
        return IndexingConstants.getRIDSize(dsd.getProperties().get(IndexingConstants.KEY_INPUT_FORMAT));
    }

    public static IBinaryComparatorFactory[] getComparatorFactories(Dataset dataset) {
        ExternalDatasetDetails dsd = (ExternalDatasetDetails) dataset.getDatasetDetails();
        return IndexingConstants.getComparatorFactories(dsd.getProperties().get(IndexingConstants.KEY_INPUT_FORMAT));
    }

    public static IBinaryComparatorFactory[] getBuddyBtreeComparatorFactories() {
        return IndexingConstants.getBuddyBtreeComparatorFactories();
    }

    public static List<ExternalFile> getSnapshotFromExternalFileSystem(Dataset dataset) throws AlgebricksException {
        ArrayList<ExternalFile> files = new ArrayList<>();
        ExternalDatasetDetails datasetDetails = (ExternalDatasetDetails) dataset.getDatasetDetails();
        try {
            // Create the file system object
            FileSystem fs = getFileSystemObject(datasetDetails.getProperties());
            // Get paths of dataset
            String path = datasetDetails.getProperties().get(ExternalDataConstants.KEY_PATH);
            String[] paths = path.split(",");

            // Add fileStatuses to files
            for (String aPath : paths) {
                FileStatus[] fileStatuses = fs.listStatus(new Path(aPath));
                for (int i = 0; i < fileStatuses.length; i++) {
                    int nextFileNumber = files.size();
                    handleFile(dataset, files, fs, fileStatuses[i], nextFileNumber);
                }
            }
            // Close file system
            fs.close();
            if (files.isEmpty()) {
                throw new AlgebricksException("File Snapshot retrieved from external file system is empty");
            }
            return files;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Exception while trying to get snapshot from external system", e);
            throw new AlgebricksException("Unable to get list of HDFS files " + e);
        }
    }

    private static void handleFile(Dataset dataset, List<ExternalFile> files, FileSystem fs, FileStatus fileStatus,
            int nextFileNumber) throws IOException {
        if (fileStatus.isDirectory()) {
            listSubFiles(dataset, fs, fileStatus, files);
        } else {
            files.add(new ExternalFile(dataset.getDataverseName(), dataset.getDatasetName(), nextFileNumber,
                    fileStatus.getPath().toUri().getPath(), new Date(fileStatus.getModificationTime()),
                    fileStatus.getLen(), ExternalFilePendingOp.NO_OP));
        }
    }

    /* list all files under the directory
     * src is expected to be a folder
     */
    private static void listSubFiles(Dataset dataset, FileSystem srcFs, FileStatus src, List<ExternalFile> files)
            throws IOException {
        Path path = src.getPath();
        FileStatus[] fileStatuses = srcFs.listStatus(path);
        for (int i = 0; i < fileStatuses.length; i++) {
            int nextFileNumber = files.size();
            if (fileStatuses[i].isDirectory()) {
                listSubFiles(dataset, srcFs, fileStatuses[i], files);
            } else {
                files.add(new ExternalFile(dataset.getDataverseName(), dataset.getDatasetName(), nextFileNumber,
                        fileStatuses[i].getPath().toUri().getPath(), new Date(fileStatuses[i].getModificationTime()),
                        fileStatuses[i].getLen(), ExternalFilePendingOp.NO_OP));
            }
        }
    }

    public static FileSystem getFileSystemObject(Map<String, String> map) throws IOException {
        Configuration conf = new Configuration();
        conf.set(ExternalDataConstants.KEY_HADOOP_FILESYSTEM_URI, map.get(ExternalDataConstants.KEY_HDFS_URL).trim());
        conf.set(ExternalDataConstants.KEY_HADOOP_FILESYSTEM_CLASS, DistributedFileSystem.class.getName());
        return FileSystem.get(conf);
    }

    public static JobSpecification buildFilesIndexReplicationJobSpec(Dataset dataset,
            List<ExternalFile> externalFilesSnapshot, MetadataProvider metadataProvider, boolean createIndex)
            throws AlgebricksException {
        IStorageComponentProvider storageComponentProvider = metadataProvider.getStorageComponentProvider();
        JobSpecification spec = RuntimeUtils.createJobSpecification();
        Pair<ILSMMergePolicyFactory, Map<String, String>> compactionInfo =
                DatasetUtil.getMergePolicyFactory(dataset, metadataProvider.getMetadataTxnContext());
        ILSMMergePolicyFactory mergePolicyFactory = compactionInfo.first;
        Map<String, String> mergePolicyFactoryProperties = compactionInfo.second;
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> secondarySplitsAndConstraint =
                metadataProvider.splitProviderAndPartitionConstraintsForFilesIndex(dataset.getDataverseName(),
                        dataset.getDatasetName(), IndexingConstants.getFilesIndexName(dataset.getDatasetName()), true);
        IFileSplitProvider secondaryFileSplitProvider = secondarySplitsAndConstraint.first;
        FilesIndexDescription filesIndexDescription = new FilesIndexDescription();
        String fileIndexName = BTreeDataflowHelperFactoryProvider.externalFileIndexName(dataset);
        Index fileIndex = MetadataManager.INSTANCE.getIndex(metadataProvider.getMetadataTxnContext(),
                dataset.getDataverseName(), dataset.getDatasetName(), fileIndexName);
        IResourceFactory localResourceMetadata = new ExternalBTreeLocalResourceMetadataFactory(
                filesIndexDescription.EXTERNAL_FILE_INDEX_TYPE_TRAITS,
                FilesIndexDescription.FILES_INDEX_COMP_FACTORIES, new int[] { 0 }, false, dataset.getDatasetId(),
                mergePolicyFactory, mergePolicyFactoryProperties, dataset.getIndexOperationTrackerFactory(fileIndex),
                dataset.getIoOperationCallbackFactory(fileIndex),
                storageComponentProvider.getMetadataPageManagerFactory());
        PersistentLocalResourceFactoryProvider localResourceFactoryProvider =
                new PersistentLocalResourceFactoryProvider(localResourceMetadata, LocalResource.ExternalBTreeResource);
        IIndexDataflowHelperFactory dataflowHelperFactory = dataset.getIndexDataflowHelperFactory(metadataProvider,
                fileIndex, null, null, mergePolicyFactory, mergePolicyFactoryProperties);
        ExternalFilesIndexOperatorDescriptor externalFilesOp =
                new ExternalFilesIndexOperatorDescriptor(spec, storageComponentProvider.getStorageManager(),
                        storageComponentProvider.getIndexLifecycleManagerProvider(), secondaryFileSplitProvider,
                        dataflowHelperFactory, localResourceFactoryProvider, externalFilesSnapshot, createIndex,
                        storageComponentProvider.getMetadataPageManagerFactory());
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, externalFilesOp,
                secondarySplitsAndConstraint.second);
        spec.addRoot(externalFilesOp);
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;
    }

    /**
     * This method create an indexing operator that index records in HDFS
     *
     * @param jobSpec
     * @param itemType
     * @param dataset
     * @param files
     * @param indexerDesc
     * @return
     * @throws AlgebricksException
     * @throws HyracksDataException
     * @throws Exception
     */
    private static Pair<ExternalScanOperatorDescriptor, AlgebricksPartitionConstraint> getIndexingOperator(
            MetadataProvider metadataProvider, JobSpecification jobSpec, IAType itemType, Dataset dataset,
            List<ExternalFile> files, RecordDescriptor indexerDesc) throws HyracksDataException, AlgebricksException {
        ExternalDatasetDetails externalDatasetDetails = (ExternalDatasetDetails) dataset.getDatasetDetails();
        Map<String, String> configuration = externalDatasetDetails.getProperties();
        IAdapterFactory adapterFactory =
                AdapterFactoryProvider.getIndexingAdapterFactory(metadataProvider.getLibraryManager(),
                        externalDatasetDetails.getAdapter(), configuration, (ARecordType) itemType, files, true, null);
        return new Pair<>(new ExternalScanOperatorDescriptor(jobSpec, indexerDesc, adapterFactory),
                adapterFactory.getPartitionConstraint());
    }

    public static Pair<ExternalScanOperatorDescriptor, AlgebricksPartitionConstraint> createExternalIndexingOp(
            JobSpecification spec, MetadataProvider metadataProvider, Dataset dataset, ARecordType itemType,
            RecordDescriptor indexerDesc, List<ExternalFile> files) throws HyracksDataException, AlgebricksException {
        return getIndexingOperator(metadataProvider, spec, itemType, dataset,
                files == null ? MetadataManager.INSTANCE
                        .getDatasetExternalFiles(metadataProvider.getMetadataTxnContext(), dataset) : files,
                indexerDesc);
    }

    /**
     * At the end of this method, we expect to have 4 sets as follows:
     * metadataFiles should contain only the files that are appended in their original state
     * addedFiles should contain new files that has number assigned starting after the max original file number
     * deletedFiles should contain files that are no longer there in the file system
     * appendedFiles should have the new file information of existing files
     * The method should return false in case of zero delta
     *
     * @param dataset
     * @param metadataFiles
     * @param addedFiles
     * @param deletedFiles
     * @param appendedFiles
     * @return
     * @throws AlgebricksException
     */
    public static boolean isDatasetUptodate(Dataset dataset, List<ExternalFile> metadataFiles,
            List<ExternalFile> addedFiles, List<ExternalFile> deletedFiles, List<ExternalFile> appendedFiles)
            throws AlgebricksException {
        boolean uptodate = true;
        int newFileNumber = metadataFiles.get(metadataFiles.size() - 1).getFileNumber() + 1;

        List<ExternalFile> fileSystemFiles = getSnapshotFromExternalFileSystem(dataset);

        // Loop over file system files < taking care of added files >
        for (ExternalFile fileSystemFile : fileSystemFiles) {
            boolean fileFound = false;
            Iterator<ExternalFile> mdFilesIterator = metadataFiles.iterator();
            while (mdFilesIterator.hasNext()) {
                ExternalFile metadataFile = mdFilesIterator.next();
                if (!fileSystemFile.getFileName().equals(metadataFile.getFileName())) {
                    continue;
                }
                // Same file name
                if (fileSystemFile.getLastModefiedTime().equals(metadataFile.getLastModefiedTime())) {
                    // Same timestamp
                    if (fileSystemFile.getSize() == metadataFile.getSize()) {
                        // Same size -> no op
                        mdFilesIterator.remove();
                        fileFound = true;
                    } else {
                        // Different size -> append op
                        metadataFile.setPendingOp(ExternalFilePendingOp.APPEND_OP);
                        fileSystemFile.setPendingOp(ExternalFilePendingOp.APPEND_OP);
                        appendedFiles.add(fileSystemFile);
                        fileFound = true;
                        uptodate = false;
                    }
                } else {
                    // Same file name, Different file mod date -> delete and add
                    metadataFile.setPendingOp(ExternalFilePendingOp.DROP_OP);
                    deletedFiles.add(new ExternalFile(metadataFile.getDataverseName(), metadataFile.getDatasetName(),
                            0, metadataFile.getFileName(), metadataFile.getLastModefiedTime(), metadataFile.getSize(),
                            ExternalFilePendingOp.DROP_OP));
                    fileSystemFile.setPendingOp(ExternalFilePendingOp.ADD_OP);
                    fileSystemFile.setFileNumber(newFileNumber);
                    addedFiles.add(fileSystemFile);
                    newFileNumber++;
                    fileFound = true;
                    uptodate = false;
                }
                if (fileFound) {
                    break;
                }
            }
            if (!fileFound) {
                // File not stored previously in metadata -> pending add op
                fileSystemFile.setPendingOp(ExternalFilePendingOp.ADD_OP);
                fileSystemFile.setFileNumber(newFileNumber);
                addedFiles.add(fileSystemFile);
                newFileNumber++;
                uptodate = false;
            }
        }

        // Done with files from external file system -> metadata files now contain both deleted files and appended ones
        // first, correct number assignment to deleted and updated files
        for (ExternalFile deletedFile : deletedFiles) {
            deletedFile.setFileNumber(newFileNumber);
            newFileNumber++;
        }
        for (ExternalFile appendedFile : appendedFiles) {
            appendedFile.setFileNumber(newFileNumber);
            newFileNumber++;
        }

        // include the remaining deleted files
        Iterator<ExternalFile> mdFilesIterator = metadataFiles.iterator();
        while (mdFilesIterator.hasNext()) {
            ExternalFile metadataFile = mdFilesIterator.next();
            if (metadataFile.getPendingOp() == ExternalFilePendingOp.NO_OP) {
                metadataFile.setPendingOp(ExternalFilePendingOp.DROP_OP);
                deletedFiles.add(new ExternalFile(metadataFile.getDataverseName(), metadataFile.getDatasetName(),
                        newFileNumber, metadataFile.getFileName(), metadataFile.getLastModefiedTime(),
                        metadataFile.getSize(), metadataFile.getPendingOp()));
                newFileNumber++;
                uptodate = false;
            }
        }
        return uptodate;
    }

    public static Dataset createTransactionDataset(Dataset dataset) {
        ExternalDatasetDetails originalDsd = (ExternalDatasetDetails) dataset.getDatasetDetails();
        ExternalDatasetDetails dsd = new ExternalDatasetDetails(originalDsd.getAdapter(), originalDsd.getProperties(),
                originalDsd.getTimestamp(), TransactionState.BEGIN);
        return new Dataset(dataset.getDataverseName(), dataset.getDatasetName(), dataset.getItemTypeDataverseName(),
                dataset.getItemTypeName(), dataset.getNodeGroupName(), dataset.getCompactionPolicy(),
                dataset.getCompactionPolicyProperties(), dsd, dataset.getHints(), DatasetType.EXTERNAL,
                dataset.getDatasetId(), dataset.getPendingOp());
    }

    public static JobSpecification buildDropFilesIndexJobSpec(MetadataProvider metadataProvider, Dataset dataset)
            throws AlgebricksException {
        String indexName = IndexingConstants.getFilesIndexName(dataset.getDatasetName());
        JobSpecification spec = RuntimeUtils.createJobSpecification();
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> splitsAndConstraint =
                metadataProvider.splitProviderAndPartitionConstraintsForFilesIndex(dataset.getDataverseName(),
                        dataset.getDatasetName(), indexName, true);
        Pair<ILSMMergePolicyFactory, Map<String, String>> compactionInfo =
                DatasetUtil.getMergePolicyFactory(dataset, metadataProvider.getMetadataTxnContext());
        String fileIndexName = BTreeDataflowHelperFactoryProvider.externalFileIndexName(dataset);
        Index fileIndex = MetadataManager.INSTANCE.getIndex(metadataProvider.getMetadataTxnContext(),
                dataset.getDataverseName(), dataset.getDatasetName(), fileIndexName);
        IIndexDataflowHelperFactory dataflowHelperFactory = dataset.getIndexDataflowHelperFactory(metadataProvider,
                fileIndex, null, null, compactionInfo.first, compactionInfo.second);
        IndexDropOperatorDescriptor btreeDrop = new IndexDropOperatorDescriptor(spec,
                metadataProvider.getStorageComponentProvider().getStorageManager(),
                metadataProvider.getStorageComponentProvider().getIndexLifecycleManagerProvider(),
                splitsAndConstraint.first, dataflowHelperFactory,
                metadataProvider.getStorageComponentProvider().getMetadataPageManagerFactory());
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, btreeDrop,
                splitsAndConstraint.second);
        spec.addRoot(btreeDrop);

        return spec;
    }

    public static JobSpecification buildFilesIndexUpdateOp(Dataset ds, List<ExternalFile> metadataFiles,
            List<ExternalFile> addedFiles, List<ExternalFile> appendedFiles, MetadataProvider metadataProvider)
            throws AlgebricksException {
        ArrayList<ExternalFile> files = new ArrayList<>();
        for (ExternalFile file : metadataFiles) {
            if (file.getPendingOp() == ExternalFilePendingOp.DROP_OP) {
                files.add(file);
            } else if (file.getPendingOp() == ExternalFilePendingOp.APPEND_OP) {
                for (ExternalFile appendedFile : appendedFiles) {
                    if (appendedFile.getFileName().equals(file.getFileName())) {
                        files.add(new ExternalFile(file.getDataverseName(), file.getDatasetName(),
                                file.getFileNumber(), file.getFileName(), file.getLastModefiedTime(),
                                appendedFile.getSize(), ExternalFilePendingOp.NO_OP));
                    }
                }
            }
        }
        for (ExternalFile file : addedFiles) {
            files.add(file);
        }
        Collections.sort(files);
        return buildFilesIndexReplicationJobSpec(ds, files, metadataProvider, false);
    }

    public static JobSpecification buildIndexUpdateOp(Dataset ds, Index index, List<ExternalFile> metadataFiles,
            List<ExternalFile> addedFiles, List<ExternalFile> appendedFiles, MetadataProvider metadataProvider)
            throws AlgebricksException {
        // Create files list
        ArrayList<ExternalFile> files = new ArrayList<>();

        for (ExternalFile metadataFile : metadataFiles) {
            if (metadataFile.getPendingOp() != ExternalFilePendingOp.APPEND_OP) {
                files.add(metadataFile);
            } else {
                metadataFile.setPendingOp(ExternalFilePendingOp.NO_OP);
                files.add(metadataFile);
            }
        }
        // add new files
        for (ExternalFile file : addedFiles) {
            files.add(file);
        }
        // add appended files
        for (ExternalFile file : appendedFiles) {
            files.add(file);
        }
        return IndexUtil.buildSecondaryIndexLoadingJobSpec(ds, index, null, null, null, null, metadataProvider, files);
    }

    public static JobSpecification buildCommitJob(Dataset ds, List<Index> indexes, MetadataProvider metadataProvider)
            throws AlgebricksException {
        JobSpecification spec = RuntimeUtils.createJobSpecification();
        Pair<ILSMMergePolicyFactory, Map<String, String>> compactionInfo =
                DatasetUtil.getMergePolicyFactory(ds, metadataProvider.getMetadataTxnContext());
        boolean temp = ds.getDatasetDetails().isTemp();
        ILSMMergePolicyFactory mergePolicyFactory = compactionInfo.first;
        Map<String, String> mergePolicyFactoryProperties = compactionInfo.second;
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> filesIndexSplitsAndConstraint =
                metadataProvider.getSplitProviderAndConstraints(ds.getDataverseName(), ds.getDatasetName(),
                        IndexingConstants.getFilesIndexName(ds.getDatasetName()), temp);
        IFileSplitProvider filesIndexSplitProvider = filesIndexSplitsAndConstraint.first;
        String fileIndexName = BTreeDataflowHelperFactoryProvider.externalFileIndexName(ds);
        Index fileIndex = MetadataManager.INSTANCE.getIndex(metadataProvider.getMetadataTxnContext(),
                ds.getDataverseName(), ds.getDatasetName(), fileIndexName);
        IIndexDataflowHelperFactory filesIndexDataflowHelperFactory = ds.getIndexDataflowHelperFactory(
                metadataProvider, fileIndex, null, null, mergePolicyFactory, mergePolicyFactoryProperties);
        IndexInfoOperatorDescriptor filesIndexInfo = new IndexInfoOperatorDescriptor(filesIndexSplitProvider,
                RuntimeComponentsProvider.RUNTIME_PROVIDER, RuntimeComponentsProvider.RUNTIME_PROVIDER);

        ArrayList<IIndexDataflowHelperFactory> treeDataflowHelperFactories = new ArrayList<>();
        ArrayList<IndexInfoOperatorDescriptor> treeInfos = new ArrayList<>();
        for (Index index : indexes) {
            if (isValidIndexName(index.getDatasetName(), index.getIndexName())) {
                Pair<IFileSplitProvider, AlgebricksPartitionConstraint> indexSplitsAndConstraint =
                        metadataProvider.getSplitProviderAndConstraints(ds.getDataverseName(), ds.getDatasetName(),
                                index.getIndexName(), temp);
                IIndexDataflowHelperFactory indexDataflowHelperFactory = ds.getIndexDataflowHelperFactory(
                        metadataProvider, index, null, null, mergePolicyFactory, mergePolicyFactoryProperties);
                treeDataflowHelperFactories.add(indexDataflowHelperFactory);
                treeInfos.add(new IndexInfoOperatorDescriptor(indexSplitsAndConstraint.first,
                        RuntimeComponentsProvider.RUNTIME_PROVIDER, RuntimeComponentsProvider.RUNTIME_PROVIDER));
            }
        }

        ExternalDatasetIndexesCommitOperatorDescriptor op = new ExternalDatasetIndexesCommitOperatorDescriptor(spec,
                filesIndexDataflowHelperFactory, filesIndexInfo, treeDataflowHelperFactories, treeInfos);

        spec.addRoot(op);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, op,
                filesIndexSplitsAndConstraint.second);
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;
    }

    public static JobSpecification buildAbortOp(Dataset ds, List<Index> indexes, MetadataProvider metadataProvider)
            throws AlgebricksException {
        JobSpecification spec = RuntimeUtils.createJobSpecification();
        Pair<ILSMMergePolicyFactory, Map<String, String>> compactionInfo =
                DatasetUtil.getMergePolicyFactory(ds, metadataProvider.getMetadataTxnContext());
        ILSMMergePolicyFactory mergePolicyFactory = compactionInfo.first;
        Map<String, String> mergePolicyFactoryProperties = compactionInfo.second;

        boolean temp = ds.getDatasetDetails().isTemp();
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> filesIndexSplitsAndConstraint =
                metadataProvider.getSplitProviderAndConstraints(ds.getDataverseName(), ds.getDatasetName(),
                        IndexingConstants.getFilesIndexName(ds.getDatasetName()), temp);
        IFileSplitProvider filesIndexSplitProvider = filesIndexSplitsAndConstraint.first;
        String fileIndexName = BTreeDataflowHelperFactoryProvider.externalFileIndexName(ds);
        Index fileIndex = MetadataManager.INSTANCE.getIndex(metadataProvider.getMetadataTxnContext(),
                ds.getDataverseName(), ds.getDatasetName(), fileIndexName);
        IIndexDataflowHelperFactory filesIndexDataflowHelperFactory = ds.getIndexDataflowHelperFactory(
                metadataProvider, fileIndex, null, null, mergePolicyFactory, mergePolicyFactoryProperties);
        IndexInfoOperatorDescriptor filesIndexInfo = new IndexInfoOperatorDescriptor(filesIndexSplitProvider,
                RuntimeComponentsProvider.RUNTIME_PROVIDER, RuntimeComponentsProvider.RUNTIME_PROVIDER);

        ArrayList<IIndexDataflowHelperFactory> treeDataflowHelperFactories = new ArrayList<>();
        ArrayList<IndexInfoOperatorDescriptor> treeInfos = new ArrayList<>();
        for (Index index : indexes) {
            if (isValidIndexName(index.getDatasetName(), index.getIndexName())) {
                Pair<IFileSplitProvider, AlgebricksPartitionConstraint> indexSplitsAndConstraint =
                        metadataProvider.getSplitProviderAndConstraints(ds.getDataverseName(), ds.getDatasetName(),
                                index.getIndexName(), temp);
                IIndexDataflowHelperFactory indexDataflowHelperFactory = ds.getIndexDataflowHelperFactory(
                        metadataProvider, index, null, null, mergePolicyFactory, mergePolicyFactoryProperties);
                treeDataflowHelperFactories.add(indexDataflowHelperFactory);
                treeInfos.add(new IndexInfoOperatorDescriptor(indexSplitsAndConstraint.first,
                        RuntimeComponentsProvider.RUNTIME_PROVIDER, RuntimeComponentsProvider.RUNTIME_PROVIDER));
            }
        }

        ExternalDatasetIndexesAbortOperatorDescriptor op = new ExternalDatasetIndexesAbortOperatorDescriptor(spec,
                filesIndexDataflowHelperFactory, filesIndexInfo, treeDataflowHelperFactories, treeInfos);

        spec.addRoot(op);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, op,
                filesIndexSplitsAndConstraint.second);
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;

    }

    public static JobSpecification buildRecoverOp(Dataset ds, List<Index> indexes, MetadataProvider metadataProvider)
            throws AlgebricksException {
        JobSpecification spec = RuntimeUtils.createJobSpecification();
        Pair<ILSMMergePolicyFactory, Map<String, String>> compactionInfo =
                DatasetUtil.getMergePolicyFactory(ds, metadataProvider.getMetadataTxnContext());
        ILSMMergePolicyFactory mergePolicyFactory = compactionInfo.first;
        Map<String, String> mergePolicyFactoryProperties = compactionInfo.second;
        boolean temp = ds.getDatasetDetails().isTemp();

        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> filesIndexSplitsAndConstraint =
                metadataProvider.getSplitProviderAndConstraints(ds.getDataverseName(), ds.getDatasetName(),
                        IndexingConstants.getFilesIndexName(ds.getDatasetName()), temp);
        IFileSplitProvider filesIndexSplitProvider = filesIndexSplitsAndConstraint.first;
        String fileIndexName = BTreeDataflowHelperFactoryProvider.externalFileIndexName(ds);
        Index fileIndex = MetadataManager.INSTANCE.getIndex(metadataProvider.getMetadataTxnContext(),
                ds.getDataverseName(), ds.getDatasetName(), fileIndexName);
        IIndexDataflowHelperFactory filesIndexDataflowHelperFactory = ds.getIndexDataflowHelperFactory(
                metadataProvider, fileIndex, null, null, mergePolicyFactory, mergePolicyFactoryProperties);
        IndexInfoOperatorDescriptor filesIndexInfo = new IndexInfoOperatorDescriptor(filesIndexSplitProvider,
                RuntimeComponentsProvider.RUNTIME_PROVIDER, RuntimeComponentsProvider.RUNTIME_PROVIDER);

        ArrayList<IIndexDataflowHelperFactory> treeDataflowHelperFactories = new ArrayList<>();
        ArrayList<IndexInfoOperatorDescriptor> treeInfos = new ArrayList<>();
        for (Index index : indexes) {
            if (isValidIndexName(index.getDatasetName(), index.getIndexName())) {
                Pair<IFileSplitProvider, AlgebricksPartitionConstraint> indexSplitsAndConstraint =
                        metadataProvider.getSplitProviderAndConstraints(ds.getDataverseName(), ds.getDatasetName(),
                                index.getIndexName(), temp);
                IIndexDataflowHelperFactory indexDataflowHelperFactory = ds.getIndexDataflowHelperFactory(
                        metadataProvider, index, null, null, mergePolicyFactory, mergePolicyFactoryProperties);
                treeDataflowHelperFactories.add(indexDataflowHelperFactory);
                treeInfos.add(new IndexInfoOperatorDescriptor(indexSplitsAndConstraint.first,
                        RuntimeComponentsProvider.RUNTIME_PROVIDER, RuntimeComponentsProvider.RUNTIME_PROVIDER));
            }
        }

        ExternalDatasetIndexesRecoverOperatorDescriptor op = new ExternalDatasetIndexesRecoverOperatorDescriptor(spec,
                filesIndexDataflowHelperFactory, filesIndexInfo, treeDataflowHelperFactories, treeInfos);

        spec.addRoot(op);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, op,
                filesIndexSplitsAndConstraint.second);
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;
    }

    public static JobSpecification compactFilesIndexJobSpec(Dataset dataset, MetadataProvider metadataProvider,
            IStorageComponentProvider storageComponentProvider) throws AlgebricksException {
        JobSpecification spec = RuntimeUtils.createJobSpecification();
        Pair<ILSMMergePolicyFactory, Map<String, String>> compactionInfo =
                DatasetUtil.getMergePolicyFactory(dataset, metadataProvider.getMetadataTxnContext());
        ILSMMergePolicyFactory mergePolicyFactory = compactionInfo.first;
        Map<String, String> mergePolicyFactoryProperties = compactionInfo.second;
        Pair<IFileSplitProvider, AlgebricksPartitionConstraint> secondarySplitsAndConstraint =
                metadataProvider.splitProviderAndPartitionConstraintsForFilesIndex(dataset.getDataverseName(),
                        dataset.getDatasetName(), IndexingConstants.getFilesIndexName(dataset.getDatasetName()), true);
        IFileSplitProvider secondaryFileSplitProvider = secondarySplitsAndConstraint.first;

        String fileIndexName = BTreeDataflowHelperFactoryProvider.externalFileIndexName(dataset);
        Index fileIndex = MetadataManager.INSTANCE.getIndex(metadataProvider.getMetadataTxnContext(),
                dataset.getDataverseName(), dataset.getDatasetName(), fileIndexName);
        IIndexDataflowHelperFactory dataflowHelperFactory = dataset.getIndexDataflowHelperFactory(metadataProvider,
                fileIndex, null, null, mergePolicyFactory, mergePolicyFactoryProperties);
        FilesIndexDescription filesIndexDescription = new FilesIndexDescription();
        LSMTreeIndexCompactOperatorDescriptor compactOp = new LSMTreeIndexCompactOperatorDescriptor(spec,
                RuntimeComponentsProvider.RUNTIME_PROVIDER, RuntimeComponentsProvider.RUNTIME_PROVIDER,
                secondaryFileSplitProvider, filesIndexDescription.EXTERNAL_FILE_INDEX_TYPE_TRAITS,
                FilesIndexDescription.FILES_INDEX_COMP_FACTORIES, new int[] { 0 }, dataflowHelperFactory,
                NoOpOperationCallbackFactory.INSTANCE, storageComponentProvider.getMetadataPageManagerFactory());
        spec.addRoot(compactOp);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, compactOp,
                secondarySplitsAndConstraint.second);
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;
    }

    public static boolean isFileIndex(Index index) {
        return index.getIndexName().equals(IndexingConstants.getFilesIndexName(index.getDatasetName()));
    }
}
