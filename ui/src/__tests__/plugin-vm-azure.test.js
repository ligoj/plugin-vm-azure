/*
 * Contract tests for plugin-vm-azure.
 *
 * Covers the tool-level manifest, i18n merge, the Azure portal link and
 * VM-name detail chip, and — end to end — the parent → child delegation:
 * when vm-azure is registered, plugin-vm's renderFeatures appends the
 * portal link for an azure node. The sibling plugin-vm + plugin-vm-aws
 * repos sit beside this one in the workspace.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { pluginRegistry, useI18nStore } from '@ligoj/host'
import pluginVmAzureDef from '../index.js'
import pluginVmDef from '../../../../plugin-vm/ui/src/index.js'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('plugin-vm-azure manifest', () => {
  it('exposes a valid tool-level manifest', () => {
    expect(pluginVmAzureDef.id).toBe('vm-azure')
    expect(typeof pluginVmAzureDef.label).toBe('string')
    expect(pluginVmAzureDef.requires).toEqual(['vm'])
    expect(pluginVmAzureDef.routes).toBeUndefined()
    expect(typeof pluginVmAzureDef.install).toBe('function')
    expect(typeof pluginVmAzureDef.feature).toBe('function')
    expect(pluginVmAzureDef.service).toBeTypeOf('object')
    expect(pluginVmAzureDef.meta).toMatchObject({ icon: expect.any(String), color: expect.any(String) })
  })

  it('merges i18n on install', () => {
    const i18n = useI18nStore()
    pluginVmAzureDef.install()
    expect(i18n.t('service:vm:azure:subscription')).toBe('Subscription')
    expect(i18n.t('service:vm:azure:portal')).toBe('Portal of this VM')
  })

  it('throws for an unknown feature', () => {
    expect(() => pluginVmAzureDef.feature('nope')).toThrow(/no feature "nope"/)
  })

  it('renderFeatures returns the Azure portal deep link when fully configured', () => {
    pluginVmAzureDef.install()
    const vnodes = pluginVmAzureDef.feature('renderFeatures', {
      node: { id: 'service:vm:azure:vm1' },
      parameters: {
        'service:vm:azure:subscription': 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        'service:vm:azure:resource-group': 'rg-prod',
        'service:vm:azure:name': 'web-01',
      },
    })
    expect(vnodes).toHaveLength(1)
    expect(vnodes[0].__v_isVNode).toBe(true)
    expect(vnodes[0].props.href).toContain('portal.azure.com')
    expect(vnodes[0].props.href).toContain('/resourceGroups/rg-prod/')
    expect(vnodes[0].props.href).toContain('/virtualMachines/web-01/overview')
    expect(vnodes[0].props.target).toBe('_blank')
  })

  it('renderFeatures returns an empty list when parameters are incomplete', () => {
    pluginVmAzureDef.install()
    expect(pluginVmAzureDef.feature('renderFeatures', {
      parameters: { 'service:vm:azure:name': 'web-01' },
    })).toEqual([])
    expect(pluginVmAzureDef.feature('renderFeatures', {})).toEqual([])
  })

  it('renderDetailsKey returns the VM name chip when present', () => {
    pluginVmAzureDef.install()
    const vnode = pluginVmAzureDef.feature('renderDetailsKey', {
      parameters: { 'service:vm:azure:name': 'web-01' },
    })
    expect(vnode).toBeTruthy()
    expect(vnode.__v_isVNode).toBe(true)
  })

  it('renderDetailsKey returns null without a VM name', () => {
    pluginVmAzureDef.install()
    expect(pluginVmAzureDef.feature('renderDetailsKey', { parameters: {} })).toBeNull()
  })
})

describe('plugin-vm → plugin-vm-azure delegation', () => {
  beforeEach(() => {
    pluginVmDef.install({ router: { addRoute() {} } })
    pluginVmAzureDef.install()
    pluginRegistry.register('vm-azure', pluginVmAzureDef)
  })
  afterEach(() => {
    pluginRegistry.remove('vm-azure')
  })

  it('appends the Azure portal link to plugin-vm renderFeatures output', () => {
    const result = pluginVmDef.feature('renderFeatures', {
      id: 9,
      node: { id: 'service:vm:azure:vm1' },
      data: {},
      parameters: {
        'service:vm:azure:subscription': 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        'service:vm:azure:resource-group': 'rg-prod',
        'service:vm:azure:name': 'web-01',
      },
    })
    // 1 parent Configure button + 1 vm-azure portal link.
    expect(result.length).toBe(2)
    for (const node of result) expect(node.__v_isVNode).toBe(true)
  })
})
