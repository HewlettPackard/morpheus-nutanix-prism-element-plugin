package com.morpheusdata.nutanix.prismelement.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismElementComputeUtility {
	static ServiceResponse doStop(HttpApiClient client, RequestConfig reqConfig, ComputeServer server, String label) {
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			def vmResults = NutanixPrismElementApiService.loadVirtualMachine(client, reqConfig, server.externalId)
			if (vmResults?.results?.power_state == "off") {
				log.debug("${label} >> vm already stopped")
				rtn.success = true
			} else {
				log.debug("${label} >> vm needs stopping")
				def vmOpts = [:]
				if (vmResults?.results?.vm_logical_timestamp)
					vmOpts.timestamp = vmResults?.results?.vm_logical_timestamp
				def stopResults = NutanixPrismElementApiService.stopVm(client, reqConfig, vmOpts, server.externalId)
				rtn.success = stopResults.success
				rtn.msg = stopResults.msg
			}
			log.debug("${label} >> success: ${rtn.success} msg: ${rtn.msg}")
		} catch (e) {
			log.error("${label} error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static ServiceResponse doStart(HttpApiClient client, RequestConfig reqConfig, ComputeServer server, String label) {
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			def vmResults = NutanixPrismElementApiService.loadVirtualMachine(client, reqConfig, server.externalId)
			if (vmResults?.results?.power_state == "on") {
				log.debug("${label} >> vm already started")
				rtn.success = true
			} else {
				log.debug("${label} >> vm needs starting")
				def vmOpts = [:]
				if (vmResults?.results?.vm_logical_timestamp)
					vmOpts.timestamp = vmResults?.results?.vm_logical_timestamp
				def startResults = NutanixPrismElementApiService.startVm(client, reqConfig, vmOpts, server.externalId)
				rtn.success = startResults.success
				rtn.msg = startResults.msg
			}
		} catch (e) {
			log.error("${label} error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static ComputeServer saveAndGet(MorpheusContext morpheusContext, ComputeServer server) {
		def saveSuccessful = morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
		if (!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}")
		}
		return morpheusContext.async.computeServer.get(server.id).blockingGet()
	}
}
