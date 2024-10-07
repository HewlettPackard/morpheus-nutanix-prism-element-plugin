package com.morpheusdata.nutanix.prismelement.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.model.Datastore
import io.reactivex.rxjava3.core.Observable

import javax.sql.DataSource

class NutanixPrismElementImageStoreDatasetProvider extends AbstractDatasetProvider<Datastore, String> {
    public static final PROVIDER_NAME = "Nutanix Image Store Provider"
    public static final PROVIDER_NAMESPACE = "com.morpheusdata.nutanix.prismelement.plugin"
    public static final PROVIDER_KEY = "nutanixContainers"
    public static final PROVIDER_DESCRIPTION = "The default image store to use with Nutanix Prism Element"

    NutanixPrismElementImageStoreDatasetProvider(Plugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
    }

    /**
     * {inheritDoc}
     */
    @Override
    DatasetInfo getInfo() {
        new DatasetInfo(
                name: PROVIDER_NAME,
                namespace: PROVIDER_NAMESPACE,
                key: PROVIDER_KEY,
                description: PROVIDER_DESCRIPTION,
        )
    }

    /**
     * {inheritDoc}
     */
    @Override
    Class<Datastore> getItemType() {
        return Datastore.class
    }

    /**
     * {inheritDoc}
     */
    @Override
    Observable<Datastore> list(DatasetQuery query) {
        morpheusContext.async.cloud.datastore.list(
                new DataQuery()
                        .withFilter('refType', 'ComputeZone')
//                        .withFilter('refId', zoneId) // TODO: how do I do this, no params to pull from?
                        .withFilter('type', 'generic')
        )
    }

    /**
     * {inheritDoc}
     */
    @Override
    Observable<Map> listOptions(DatasetQuery query) {
        list(query).map { [name: it.name, value: it.externalId] }
    }

    /**
     * {inheritDoc}
     */
    @Override
    Datastore fetchItem(Object value) {
        def rtn = null
         if(value instanceof String) {
            rtn = item((String)value)
        }
        return rtn

    }

    /**
     * {inheritDoc}
     */
    @Override
    Datastore item(String value) {
        def query = new DatasetQuery().withFilter("externalId", value)
        query.max = 1
        return list(query as DatasetQuery)
                .toList()
                .blockingGet()
                .first()
    }

    /**
     * {inheritDoc}
     */
    @Override
    String itemName(Datastore item) {
        return item.name
    }

    /**
     * {inheritDoc}
     */
    @Override
    String itemValue(Datastore item) {
        return item.id
    }

    /**
     * {inheritDoc}
     */
    @Override
    boolean isPlugin() {
        return true
    }
}
