package com.morpheusdata.nutanix.prismelement.plugin.backup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.MorpheusBackupProvider

class NutanixPrismElementBackupProvider extends MorpheusBackupProvider {
    NutanixPrismElementBackupProvider(Plugin plugin, MorpheusContext morpheusContext) {
        super(plugin, morpheusContext)

        NutanixPrismElementBackupTypeProvider nutanixPrismSnapshotProvider = new NutanixPrismElementBackupTypeProvider(plugin, morpheusContext)
        plugin.registerProvider(nutanixPrismSnapshotProvider)
        addScopedProvider(nutanixPrismSnapshotProvider, "nutanix", null)
    }
}
