# plugin-vm-azure — Vue UI

Vue source for the **vm-azure** tool plugin (`service:vm:azure`), the
Azure implementation of the `vm` service. Compiled by Vite into the Maven
plugin JAR at `../src/main/resources/META-INF/resources/webjars/vm-azure/vue/`,
served by the host at `/main/vm-azure/vue/index.js`.

This is a **tool-level** plugin — see the host's `app-ui/REWRITE_VUEJS.md`
("Tool-level (sub-plugin) variant") for the full contract. It ships:

- **i18n** — Azure parameter labels (`service:vm:azure:*`) used by the
  subscribe wizard's auto-rendered parameter form.
- **`renderFeatures`** — a deep link to this VM in the Azure portal.
- **`renderDetailsKey`** — a chip showing the VM name.

It declares `requires: ['vm']`; the parent `plugin-vm` merges the row
features above into its subscription rows via its delegation hook
(`subPluginIdFor` maps `service:vm:azure:*` → `vm-azure`).

## Commands

```bash
npm install
npm run build   # → ../src/main/resources/.../webjars/vm-azure/vue/
npm run lint
npm test        # vitest — manifest + feature contract tests
npm run dev     # standalone dev harness on :5177
```

For real integration testing, run the host's vite dev server
(`ligoj/app-ui/src/main/webapp`, `npm run dev`) which proxies
`/ligoj/main/vm-azure/vue/*` to the freshly built bundle. The cycle:
edit source → `npm run build` here → host browser auto-reloads.
