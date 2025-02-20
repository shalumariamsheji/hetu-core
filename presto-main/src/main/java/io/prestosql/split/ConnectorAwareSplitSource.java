/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.split;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.prestosql.execution.Lifespan;
import io.prestosql.metadata.Split;
import io.prestosql.spi.connector.CatalogName;
import io.prestosql.spi.connector.ConnectorPartitionHandle;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorSplitSource;
import io.prestosql.spi.connector.ConnectorSplitSource.ConnectorSplitBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.MoreFutures.toListenableFuture;
import static java.util.Objects.requireNonNull;

public class ConnectorAwareSplitSource
        implements SplitSource
{
    private final CatalogName catalogName;
    private final ConnectorSplitSource source;

    public ConnectorAwareSplitSource(CatalogName catalogName, ConnectorSplitSource source)
    {
        this.catalogName = requireNonNull(catalogName, "catalogName is null");
        this.source = requireNonNull(source, "source is null");
    }

    @Override
    public CatalogName getCatalogName()
    {
        return catalogName;
    }

    @Override
    public ListenableFuture<SplitBatch> getNextBatch(ConnectorPartitionHandle partitionHandle, Lifespan lifespan, int maxSize)
    {
        ListenableFuture<ConnectorSplitBatch> nextBatch = toListenableFuture(source.getNextBatch(partitionHandle, maxSize));
        return Futures.transform(nextBatch, splitBatch -> {
            ImmutableList.Builder<Split> result = ImmutableList.builder();
            for (ConnectorSplit connectorSplit : splitBatch.getSplits()) {
                result.add(new Split(catalogName, connectorSplit, lifespan));
            }
            return new SplitBatch(result.build(), splitBatch.isNoMoreSplits());
        }, directExecutor());
    }

    @Override
    public void close()
    {
        source.close();
    }

    @Override
    public boolean isFinished()
    {
        return source.isFinished();
    }

    @Override
    public Optional<List<Object>> getTableExecuteSplitsInfo()
    {
        return source.getTableExecuteSplitsInfo();
    }

    @Override
    public String toString()
    {
        return catalogName + ":" + source;
    }

    public List<Split> groupSmallSplits(List<Split> pendingSplits, Lifespan lifespan, int maxGroupSize)
    {
        List<ConnectorSplit> connectorSplits = new ArrayList<>();
        for (Split split : pendingSplits) {
            connectorSplits.add(split.getConnectorSplit());
        }
        List<ConnectorSplit> connectorSplits1 = source.groupSmallSplits(connectorSplits, maxGroupSize);
        ImmutableList.Builder<Split> result = ImmutableList.builder();
        for (ConnectorSplit connectorSplit : connectorSplits1) {
            result.add(new Split(catalogName, connectorSplit, lifespan));
        }
        return result.build();
    }
}
