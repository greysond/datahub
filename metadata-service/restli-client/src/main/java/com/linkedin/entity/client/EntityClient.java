package com.linkedin.entity.client;

import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.StringArray;
import com.linkedin.entity.EntitiesDoAutocompleteRequestBuilder;
import com.linkedin.entity.EntitiesDoBatchGetTotalEntityCountRequestBuilder;
import com.linkedin.entity.EntitiesDoBatchIngestRequestBuilder;
import com.linkedin.entity.EntitiesDoBrowseRequestBuilder;
import com.linkedin.entity.EntitiesDoGetBrowsePathsRequestBuilder;
import com.linkedin.entity.EntitiesDoGetTotalEntityCountRequestBuilder;
import com.linkedin.entity.EntitiesDoIngestRequestBuilder;
import com.linkedin.entity.EntitiesDoSearchRequestBuilder;
import com.linkedin.entity.EntitiesDoSetWritableRequestBuilder;
import com.linkedin.entity.EntitiesRequestBuilders;
import com.linkedin.entity.Entity;
import com.linkedin.entity.EntityArray;
import com.linkedin.metadata.browse.BrowseResult;
import com.linkedin.metadata.query.AutoCompleteResult;
import com.linkedin.metadata.query.SearchResult;
import com.linkedin.mxe.SystemMetadata;
import com.linkedin.r2.RemoteInvocationException;
import com.linkedin.restli.client.BatchGetEntityRequest;
import com.linkedin.restli.client.Client;
import com.linkedin.restli.client.GetRequest;
import com.linkedin.restli.client.Request;
import com.linkedin.restli.client.Response;
import com.linkedin.restli.client.RestLiResponseException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.metadata.dao.utils.QueryUtils.newFilter;


public class EntityClient {

    private static final EntitiesRequestBuilders ENTITIES_REQUEST_BUILDERS = new EntitiesRequestBuilders();

    private static final String ACTION_INGEST = "ingest";
    private static final String PARAM_ENTITY = "entity";
    private static final String PARAM_SYSTEM_METADATA = "systemMetadata";
    private static final String RESOURCE_NAME = "entities";

    private final Client _client;
    private final Logger _logger = LoggerFactory.getLogger("EntityClient");

    public EntityClient(@Nonnull final Client restliClient) {
        _client = restliClient;
    }

    private <T> Response<T> sendClientRequest(Request<T> request) throws RemoteInvocationException {
        try {
            return _client.sendRequest(request).getResponse();
        } catch (RemoteInvocationException e) {
            if (e instanceof RestLiResponseException) {
                RestLiResponseException restliException = (RestLiResponseException) e;
                if (restliException.getStatus() == 404) {
                    _logger.error("ERROR: Your datahub-frontend instance version is ahead of your gms instance. "
                        + "Please update your gms to the latest Datahub release");
                    System.exit(1);
                }
             }
            throw e;
        }
    }

    @Nonnull
    public Entity get(@Nonnull final Urn urn) throws RemoteInvocationException {
        final GetRequest<Entity> getRequest = ENTITIES_REQUEST_BUILDERS.get()
                .id(urn.toString())
                .build();
        return sendClientRequest(getRequest).getEntity();
    }

    @Nonnull
    public Map<Urn, Entity> batchGet(@Nonnull final Set<Urn> urns) throws RemoteInvocationException {

        final Integer batchSize = 25;
        final AtomicInteger index = new AtomicInteger(0);

        final Collection<List<Urn>> entityUrnBatches = urns.stream()
                .collect(Collectors.groupingBy(x -> index.getAndIncrement() / batchSize))
                .values();

        final Map<Urn, Entity> response = new HashMap<>();

        for (List<Urn> urnsInBatch : entityUrnBatches) {
            BatchGetEntityRequest<String, Entity> batchGetRequest =
                    ENTITIES_REQUEST_BUILDERS.batchGet()
                            .ids(urnsInBatch.stream().map(urn -> urn.toString()).collect(Collectors.toSet()))
                            .build();
            final Map<Urn, Entity> batchResponse = sendClientRequest(batchGetRequest).getEntity().getResults()
                    .entrySet().stream().collect(Collectors.toMap(
                            entry -> {
                                try {
                                    return Urn.createFromString(entry.getKey());
                                } catch (URISyntaxException e) {
                                   throw new RuntimeException(String.format("Failed to create Urn from key string %s", entry.getKey()));
                                }
                            },
                            entry -> entry.getValue().getEntity())
                    );
            response.putAll(batchResponse);
        }
        return response;
    }

    /**
     * Gets browse snapshot of a given path
     *
     * @param query search query
     * @param field field of the dataset
     * @param requestFilters autocomplete filters
     * @param limit max number of autocomplete results
     * @throws RemoteInvocationException
     */
    @Nonnull
    public AutoCompleteResult autoComplete(@Nonnull String entityType, @Nonnull String query,
        @Nonnull Map<String, String> requestFilters,
        @Nonnull int limit,
        @Nullable String field) throws RemoteInvocationException {
        EntitiesDoAutocompleteRequestBuilder requestBuilder = ENTITIES_REQUEST_BUILDERS
            .actionAutocomplete()
            .entityParam(entityType)
            .queryParam(query)
            .fieldParam(field)
            .filterParam(newFilter(requestFilters))
            .limitParam(limit);
        return sendClientRequest(requestBuilder.build()).getEntity();
    }

    /**
     * Gets browse snapshot of a given path
     *
     * @param query search query
     * @param requestFilters autocomplete filters
     * @param limit max number of autocomplete results
     * @throws RemoteInvocationException
     */
    @Nonnull
    public AutoCompleteResult autoComplete(@Nonnull String entityType, @Nonnull String query,
        @Nonnull Map<String, String> requestFilters,
        @Nonnull int limit) throws RemoteInvocationException {
        EntitiesDoAutocompleteRequestBuilder requestBuilder = ENTITIES_REQUEST_BUILDERS
            .actionAutocomplete()
            .entityParam(entityType)
            .queryParam(query)
            .filterParam(newFilter(requestFilters))
            .limitParam(limit);
        return sendClientRequest(requestBuilder.build()).getEntity();
    }

    /**
     * Gets browse snapshot of a given path
     *
     * @param entityType entity type being browse
     * @param path path being browsed
     * @param requestFilters browse filters
     * @param start start offset of first dataset
     * @param limit max number of datasets
     * @throws RemoteInvocationException
     */
    @Nonnull
    public BrowseResult browse(@Nonnull String entityType, @Nonnull String path, @Nullable Map<String, String> requestFilters,
        int start, int limit) throws RemoteInvocationException {
        EntitiesDoBrowseRequestBuilder requestBuilder = ENTITIES_REQUEST_BUILDERS
            .actionBrowse()
            .pathParam(path)
            .entityParam(entityType)
            .startParam(start)
            .limitParam(limit);
        if (requestFilters != null) {
            requestBuilder.filterParam(newFilter(requestFilters));
        }
        return sendClientRequest(requestBuilder.build()).getEntity();
    }

    public Response<Void> update(@Nonnull final Entity entity) throws RemoteInvocationException {
        EntitiesDoIngestRequestBuilder requestBuilder =
            ENTITIES_REQUEST_BUILDERS.actionIngest().entityParam(entity);

        return sendClientRequest(requestBuilder.build());
    }

    public Response<Void> updateWithSystemMetadata(@Nonnull final Entity entity,
        @Nullable final SystemMetadata systemMetadata) throws RemoteInvocationException {
        if (systemMetadata == null) {
            return update(entity);
        }

        EntitiesDoIngestRequestBuilder requestBuilder =
            ENTITIES_REQUEST_BUILDERS.actionIngest().entityParam(entity).systemMetadataParam(systemMetadata);

        return sendClientRequest(requestBuilder.build());
    }

    public Response<Void> batchUpdate(@Nonnull final Set<Entity> entities) throws RemoteInvocationException {
        EntitiesDoBatchIngestRequestBuilder requestBuilder =
            ENTITIES_REQUEST_BUILDERS.actionBatchIngest().entitiesParam(new EntityArray(entities));

        return sendClientRequest(requestBuilder.build());
    }

    /**
     * Searches for datasets matching to a given query and filters
     *
     * @param input search query
     * @param requestFilters search filters
     * @param start start offset for search results
     * @param count max number of search results requested
     * @return Snapshot key
     * @throws RemoteInvocationException
     */
    @Nonnull
    public SearchResult search(@Nonnull String entity, @Nonnull String input, @Nonnull Map<String, String> requestFilters,
        int start, int count) throws RemoteInvocationException {

        return search(entity, input, null, requestFilters, start, count);
    }

    @Nonnull
    public SearchResult search(@Nonnull String entity, @Nonnull String input, @Nullable StringArray aspectNames,
        @Nullable Map<String, String> requestFilters, int start, int count)
        throws RemoteInvocationException {

        final EntitiesDoSearchRequestBuilder requestBuilder = ENTITIES_REQUEST_BUILDERS.actionSearch()
            .entityParam(entity)
            .inputParam(input)
            .filterParam(newFilter(requestFilters))
            .startParam(start)
            .countParam(count);

        return sendClientRequest(requestBuilder.build()).getEntity();
    }

    /**
     * Gets browse path(s) given dataset urn
     *
     * @param urn urn for the entity
     * @return list of paths given urn
     * @throws RemoteInvocationException
     */
    @Nonnull
    public StringArray getBrowsePaths(@Nonnull Urn urn) throws RemoteInvocationException {
        EntitiesDoGetBrowsePathsRequestBuilder requestBuilder = ENTITIES_REQUEST_BUILDERS
            .actionGetBrowsePaths()
            .urnParam(urn);
        return sendClientRequest(requestBuilder.build()).getEntity();
    }

    public void setWritable(boolean canWrite) throws RemoteInvocationException {
        EntitiesDoSetWritableRequestBuilder requestBuilder =
            ENTITIES_REQUEST_BUILDERS.actionSetWritable().valueParam(canWrite);
        sendClientRequest(requestBuilder.build());
    }

    @Nonnull
    public long getTotalEntityCount(@Nonnull String entityName) throws RemoteInvocationException {
        EntitiesDoGetTotalEntityCountRequestBuilder requestBuilder =
            ENTITIES_REQUEST_BUILDERS.actionGetTotalEntityCount().entityParam(entityName);
        return sendClientRequest(requestBuilder.build()).getEntity();
    }

    @Nonnull
    public Map<String, Long> batchGetTotalEntityCount(@Nonnull List<String> entityName) throws RemoteInvocationException {
        EntitiesDoBatchGetTotalEntityCountRequestBuilder requestBuilder =
            ENTITIES_REQUEST_BUILDERS.actionBatchGetTotalEntityCount().entitiesParam(new StringArray(entityName));
        return sendClientRequest(requestBuilder.build()).getEntity();
    }
}
