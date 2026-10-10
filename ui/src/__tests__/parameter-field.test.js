/*
 * The subscribe wizard's input of `service:vm:azure:name`: the virtual machines of the
 * selected instance, searched as the user types (the host LjRemoteSearchField).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import def from '../index.js'

const PARAMETER = { id: 'service:vm:azure:name', type: 'TEXT', mandatory: true }

function jsonResponse(body) {
  return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => body }
}
// Expose the bindings of the field; the test types through `update:search`
const inputStub = (name) => ({ name, props: ['items'], emits: ['update:search', 'update:modelValue'], template: '<input />' })
const stubs = { LigojAutocomplete: inputStub('LigojAutocomplete'), LigojCombobox: inputStub('LigojCombobox') }

beforeEach(() => {
  // The search waits for a pause in the typing
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  setActivePinia(createPinia())
  def.install()
  globalThis.fetch = vi.fn(async () => jsonResponse([{ id: 'web-1', name: 'web-1' }]))
})
afterEach(() => { vi.useRealTimers() })

describe('service:vm:azure:name parameter field', () => {
  it('is a remote search to subscribe, the default input to edit a node or for another parameter', () => {
    const field = def.feature('parameterField', { parameter: PARAMETER, isNode: false })
    expect(field).toBeTruthy()
    // The same component on each render, else the wizard would remount the input
    expect(def.feature('parameterField', { parameter: PARAMETER, isNode: false })).toBe(field)
    expect(def.feature('parameterField', { parameter: PARAMETER, isNode: true })).toBeNull()
    expect(def.feature('parameterField', { parameter: { id: 'service:vm:azure:subscription' }, isNode: false })).toBeNull()
  })

  it('searches the virtual machines and stores the virtual machine name', async () => {
    const Field = def.feature('parameterField', { parameter: PARAMETER, isNode: false })
    const w = mount(Field, { props: { modelValue: '', parameter: PARAMETER, formValues: {}, mode: 'link', isNode: false, instanceNodeId: 'service:vm:azure:local' }, global: { stubs } })
    const input = w.findComponent({ name: 'LigojAutocomplete' })
    input.vm.$emit('update:search', 'web')
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(fetch.mock.calls[0][0]).toBe('rest/service/vm/azure/service%3Avm%3Aazure%3Alocal/web')
    expect(input.props('items')).toEqual([{ title: 'web-1', subtitle: null, value: 'web-1' }])
  })
})
