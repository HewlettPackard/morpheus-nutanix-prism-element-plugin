package com.morpheusdata.nutanix.prismelement.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.data.NotDataFilter
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.model.VirtualImage
import io.reactivex.rxjava3.core.Observable

class NutanixPrismElementVirtualImageDatasetProvider  extends AbstractDatasetProvider<VirtualImage, String> {
	public static final PROVIDER_NAME = "Nutanix Virtual Image Provider"
	public static final PROVIDER_NAMESPACE = "com.morpheusdata.nutanix.prismelement.plugin"
	public static final PROVIDER_KEY = "npeVirtualImages"
	public static final PROVIDER_DESCRIPTION = "Nutanix virtual images"

	NutanixPrismElementVirtualImageDatasetProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	/**
	 * {@inheritDoc}
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
	 * {@inheritDoc}
	 */
	@Override
	Class<VirtualImage> getItemType() {
		return VirtualImage.class
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Observable<VirtualImage> list(DatasetQuery query) {
		Long cloudId = query.get("zoneId")?.toLong()
		Long accountId = query.get("accountId")?.toLong()
		return morpheusContext.async.virtualImage.list(
			new DataQuery()
				.withFilters(
					new DataOrFilter(
						new DataFilter('visibility', 'public'),
						new DataFilter('accounts.id', accountId),
						new DataFilter('owner.id', accountId),
					),
					new DataOrFilter(
						new NotDataFilter('status', 'in', ['Saving','Failed','Converting','Queued']),
						new DataFilter('status', null),
					),
					new DataFilter('deleted', false),
					new DataOrFilter(
						new DataFilter('imageType', 'disk'),
						new DataFilter('imageType', 'qcow2'),
					),
					new DataOrFilter(
						new DataFilter('category', "nutanix.acropolis.image.${cloudId}"),
						new DataFilter('userUploaded', true),
					),
				).withSort('name')
		)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Observable<Map> listOptions(DatasetQuery query) {
		list(query).map { [name: it.name, value: it.id] }
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	VirtualImage fetchItem(Object value) {
		def rtn = null
		if (value instanceof String) {
			rtn = item((String) value)
		}
		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	VirtualImage item(String value) {
		def query = new DatasetQuery().withFilter("id", value)
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
	String itemName(VirtualImage item) {
		return item.name
	}

	/**
	 * {inheritDoc}
	 */
	@Override
	String itemValue(VirtualImage item) {
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
