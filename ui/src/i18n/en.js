/*
 * English labels for plugin-vm-azure.
 *
 * Flat keys (the host's vue-i18n uses messageResolver: obj => obj[path],
 * no dot/colon traversal) so `service:vm:azure:*` ids resolve as literal
 * lookups. The keys mirror the parameter ids declared in the plugin's
 * src/main/resources/csv/parameter.csv so the subscribe wizard's
 * auto-rendered parameter form shows friendly labels.
 */
export default {
  'service:vm:azure:subscription': 'Subscription',
  'service:vm:azure:application': 'Application ID',
  'service:vm:azure:key': 'Application Key',
  'service:vm:azure:tenant': 'Tenant ID',
  'service:vm:azure:resource-group': 'Resource Group',
  'service:vm:azure:name': 'Name',
  'service:vm:azure:location': 'Location',
  'service:vm:azure:portal': 'Portal of this VM',
}
