/*
 * Service layer for plugin "vm-azure".
 *
 * Tool-level plugin (lives at `service:vm:azure`). It augments the parent
 * `plugin-vm` subscription rows. The host's `PluginFeatures` resolves a
 * row to the *service*-level plugin (segment 2 of the node id, i.e.
 * `vm`), then the parent's renderer delegates tool-specific VNodes to us
 * via `subPluginIdFor(...)` (see REWRITE_VUEJS.md "Parent-to-child
 * delegation").
 *
 *   - renderFeatures        → a deep link to this VM in the Azure portal.
 *   - renderDetailsKey      → the VM name chip (the resource identifier),
 *     mirroring the legacy `renderKey('service:vm:azure:name')`.
 *
 * The legacy `renderDetailsKey` carousel also showed live
 * `subscription.data.vm` fields (os, cpu/ram/disk, location) — that data
 * contract belongs to the not-yet-fully-wired parent VM model, so we keep
 * the chip to the always-present parameter (the VM name) and let the
 * parent own live status.
 *
 * Kept free of Vue SFC imports so it can be unit-tested without a DOM.
 */
import { renderServiceLink, renderDetailsChip, useI18nStore } from '@ligoj/host'

const PARAM_SUBSCRIPTION = 'service:vm:azure:subscription'
const PARAM_RESOURCE_GROUP = 'service:vm:azure:resource-group'
const PARAM_NAME = 'service:vm:azure:name'

/**
 * Deep link to this VM's blade in the Azure portal. Mirrors the legacy
 * `renderFeatures`: rendered only when subscription, resource group and
 * VM name are all known.
 */
function renderFeatures(subscription) {
  const params = subscription?.parameters
  if (!params) return []
  const sub = params[PARAM_SUBSCRIPTION]
  const group = params[PARAM_RESOURCE_GROUP]
  const name = params[PARAM_NAME]
  if (!sub || !group || !name) return []
  const { t } = useI18nStore()
  return [
    renderServiceLink({
      icon: 'mdi-microsoft-azure',
      href:
        `https://portal.azure.com/#resource/subscriptions/${sub}` +
        `/resourceGroups/${group}/providers/Microsoft.Compute/virtualMachines/${name}/overview`,
      title: t('service:vm:azure:portal'),
    }),
  ]
}

/**
 * Resource-identifier chip for the subscription details column — the VM
 * name from the subscription parameters. Mirrors the legacy
 * `renderKey('service:vm:azure:name')`.
 */
function renderDetailsKey(subscription) {
  const name = subscription?.parameters?.[PARAM_NAME]
  if (!name) return null
  const { t } = useI18nStore()
  return renderDetailsChip({ icon: 'mdi-microsoft-azure', text: name, title: t('service:vm:azure:name') })
}

export default { renderFeatures, renderDetailsKey }
