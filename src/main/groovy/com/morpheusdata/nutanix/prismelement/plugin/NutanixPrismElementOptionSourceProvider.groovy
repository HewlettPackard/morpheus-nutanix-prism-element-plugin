/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.morpheusdata.nutanix.prismelement.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismElementOptionSourceProvider extends AbstractOptionSourceProvider {

	NutanixPrismElementPlugin plugin
	MorpheusContext morpheusContext

	NutanixPrismElementOptionSourceProvider(NutanixPrismElementPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return 'nutanix-prism-element-option-source'
	}

	@Override
	String getName() {
		return 'Nutanix Prism Element Option Source'
	}

	@Override
	List<String> getMethodNames() {
		return new ArrayList<String>(['nutanixPrismElementWindowsNicConfigModeOptions'])
	}

	def nutanixPrismElementWindowsNicConfigModeOptions(args) {
		return [
			[name: 'Inline (Unattend.xml)', value: 'unattend'],
			[name: 'SetupComplete.cmd (Recommended)', value: 'setupComplete']
		]
	}
}
