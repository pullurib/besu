/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.sync.snapsync;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.sync.PivotBlockSelector;
import org.hyperledger.besu.ethereum.eth.sync.QuickSyncActions;
import org.hyperledger.besu.ethereum.eth.sync.QuickSyncDownloader;
import org.hyperledger.besu.ethereum.eth.sync.QuickSyncState;
import org.hyperledger.besu.ethereum.eth.sync.QuickSyncStateStorage;
import org.hyperledger.besu.ethereum.eth.sync.SyncMode;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.context.SnapSyncStatePersistenceManager;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.SnapDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.eth.sync.worldstate.DownloaderFactory;
import org.hyperledger.besu.ethereum.eth.sync.worldstate.WorldStateDownloader;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ScheduleBasedBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.trie.CompactEncoding;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.metrics.SyncDurationMetrics;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.services.tasks.InMemoryTasksPriorityQueues;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnapDownloaderFactory extends DownloaderFactory {

  private static final Logger LOG = LoggerFactory.getLogger(SnapDownloaderFactory.class);

  public static Optional<QuickSyncDownloader<?>> createSnapDownloader(
      final SnapSyncStatePersistenceManager snapContext,
      final PivotBlockSelector pivotBlockSelector,
      final SynchronizerConfiguration syncConfig,
      final Path dataDirectory,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final MetricsSystem metricsSystem,
      final EthContext ethContext,
      final WorldStateStorageCoordinator worldStateStorageCoordinator,
      final SyncState syncState,
      final Clock clock,
      final SyncDurationMetrics syncDurationMetrics) {

    final Path fastSyncDataDirectory = dataDirectory.resolve(FAST_SYNC_FOLDER);
    final QuickSyncStateStorage quickSyncStateStorage =
        new QuickSyncStateStorage(fastSyncDataDirectory);

    if (SyncMode.isFullSync(syncConfig.getSyncMode())) {
      if (quickSyncStateStorage.isFastSyncInProgress()) {
        throw new IllegalStateException(
            "Unable to change the sync mode when snap sync is incomplete, please restart with snap sync mode");
      } else {
        return Optional.empty();
      }
    }

    ensureDirectoryExists(fastSyncDataDirectory.toFile());

    final QuickSyncState quickSyncState =
        quickSyncStateStorage.loadState(ScheduleBasedBlockHeaderFunctions.create(protocolSchedule));
    if (syncState.isResyncNeeded()) {
      snapContext.clear();
      syncState
          .getAccountToRepair()
          .ifPresent(
              address ->
                  snapContext.addAccountToHealingList(
                      CompactEncoding.bytesToPath(address.addressHash())));
    } else if (quickSyncState.getPivotBlockHeader().isEmpty()
        && protocolContext.getBlockchain().getChainHeadBlockNumber()
            != BlockHeader.GENESIS_BLOCK_NUMBER) {
      LOG.info(
          "Snap sync was requested, but cannot be enabled because the local blockchain is not empty.");
      return Optional.empty();
    }

    final SnapSyncProcessState snapSyncState = new SnapSyncProcessState(quickSyncState);

    final InMemoryTasksPriorityQueues<SnapDataRequest> snapTaskCollection =
        createSnapWorldStateDownloaderTaskCollection();
    final WorldStateDownloader snapWorldStateDownloader =
        new SnapWorldStateDownloader(
            ethContext,
            snapContext,
            protocolContext,
            worldStateStorageCoordinator,
            snapTaskCollection,
            syncConfig.getSnapSyncConfiguration(),
            syncConfig.getWorldStateRequestParallelism(),
            syncConfig.getWorldStateMaxRequestsWithoutProgress(),
            syncConfig.getWorldStateMinMillisBeforeStalling(),
            clock,
            metricsSystem,
            syncDurationMetrics);
    final QuickSyncDownloader<SnapDataRequest> fastSyncDownloader =
        new SnapSyncDownloader(
            new QuickSyncActions(
                syncConfig,
                worldStateStorageCoordinator,
                protocolSchedule,
                protocolContext,
                ethContext,
                syncState,
                pivotBlockSelector,
                metricsSystem),
            worldStateStorageCoordinator,
            snapWorldStateDownloader,
            quickSyncStateStorage,
            snapTaskCollection,
            fastSyncDataDirectory,
            snapSyncState,
            syncDurationMetrics);
    syncState.setWorldStateDownloadStatus(snapWorldStateDownloader);
    return Optional.of(fastSyncDownloader);
  }

  protected static InMemoryTasksPriorityQueues<SnapDataRequest>
      createSnapWorldStateDownloaderTaskCollection() {
    return new InMemoryTasksPriorityQueues<>();
  }
}
