/*
 * Plugin "vm-azure" — Azure implementation of plugin-vm.
 *
 * Tool-level plugin: lives at `service:vm:azure` in the node tree. It does
 * not own routes or a top-level component (the legacy UI had no view —
 * only row features). It augments the parent `plugin-vm` via:
 *
 *   - i18n: Azure-specific parameter labels (subscription, application,
 *     key, tenant, resource group, name) so the subscribe wizard's
 *     auto-rendered parameter form shows friendly names.
 *   - feature('renderFeatures', subscription): the Azure portal deep link.
 *   - feature('renderDetailsKey', subscription): the VM name chip.
 *
 * The parent `plugin-vm` merges these into its subscription-row output
 * through its `subPluginIdFor(...)` delegation hook.
 *
 * Authored as source — compiled to `/main/vm-azure/vue/index.js` by Vite.
 * Shared host surface (stores, components) is imported from `@ligoj/host`
 * and kept external at build so plugin and host share the same instances.
 */
import { useI18nStore } from '@ligoj/host'
import enMessages from './i18n/en.js'
import frMessages from './i18n/fr.js'
import service from './service.js'

const features = {
  renderFeatures: service.renderFeatures,
  renderDetailsKey: service.renderDetailsKey,
}

export default {
  id: 'vm-azure',
  label: 'VM Azure',
  // Declared dependency on the parent service-level plugin: it provides
  // the subscription-row chrome and the delegation hook that pulls our
  // VNodes in. The loader awaits requires before calling our install(),
  // so the parent's i18n is in the store before our labels render.
  requires: ['vm'],
  // No routes / component — Azure screens and the parameter form come
  // from the parent's wizard.
  install() {
    const i18n = useI18nStore()
    i18n.merge(enMessages, 'en')
    i18n.merge(frMessages, 'fr')
  },
  feature(action, ...args) {
    const fn = features[action]
    if (!fn) throw new Error(`Plugin "vm-azure" has no feature "${action}"`)
    return fn(...args)
  },
  service,
  meta: { icon: 'mdi-microsoft-azure', color: 'blue-darken-2' },
}

export { service }
