# React Props Doc

Plugin para IntelliJ IDEA que muestra y revisa las props de componentes React sin tener que escribir JSDoc, y
ayuda con las traducciones de páginas (`getLanguagePage('página')`). Nació para el monorepo *bancapersonas*
(React + Module Federation + MUI), pero funciona en cualquier proyecto React con `propTypes` o MUI.

- **IDE:** IntelliJ IDEA Ultimate 2026.3 o superior (requiere los plugins JavaScript y JSON incluidos).
- **Versión actual:** 1.9.0

## Instalación

1. Compila: `./gradlew buildPlugin` → `build/distributions/react-props-doc-<versión>.zip`
2. IntelliJ: *Settings → Plugins → ⚙ → Install Plugin from Disk…* y elige el `.zip`.
3. Reinicia el IDE.

Ajustes: *Settings → Tools → React Props Doc*. Avisos: *Settings → Editor → Inspections → React Props Doc*.

## Mejoras realizadas

### Documentación al pasar el mouse
| Función | Detalle |
|---|---|
| Props de tus componentes | Desde `propTypes`, `defaultProps` y la desestructuración (`({ a, b = 1 })` o `const { a } = props`), con tipo, requerida y valor por defecto. Soporta `memo`/`forwardRef` e imports federados vía `jsconfig.json`. |
| Props de MUI | Lee los `.d.ts`: `XxxOwnProps` / `BaseXxxProps` / `XxxProps`, con tipo, `@default` y descripción. Sigue re-exportaciones (`index.d.ts`) y `Box` (vía `@mui/system`). |
| Props heredadas | ButtonBase, Paper, Modal, Breakpoints… con tipo y valor por defecto, sin las excluidas por `Omit` / `DistributiveOmit` / `StandardProps<X, 'a'>`; atributos HTML del elemento raíz (`<button>`, `<div>`…) con sus tipos de `@types/react`. En componentes ButtonBase, `type` muestra `(= 'button')`. |
| Firma resumida | Oculta la firma larga del IDE cuando se muestran las props (configurable). |
| Hover sobre una prop | En `openDialog=`: componente, si es requerida, valor por defecto y valores de `oneOf`. |
| Ficha de hooks | Parámetros y lo que devuelven (estado, función, JSX, arreglo…). Incluye `...spread` de objetos conocidos y resultados armados por partes (`result.a = …; return result`). |
| Traducciones | Sobre una clave de `getLanguagePage`: el texto en cada idioma (ES/EN/KI), avisa si falta y explica cómo agregarla con el atajo real del keymap. |

### Redux (`useSelector(state => state.x)`)
- `state.│` autocompleta las partes del estado (`catalog`, `bankList`…) y `state.catalog.│` las claves de su
  estado inicial. Soporta `combineReducers`, `configureStore({ reducer: { … } })` y `createSlice`.
- Al pasar el mouse sobre `state.catalog`: el reducer, su estado inicial y las acciones que maneja
  (`case fetchDataCatalog:`). Si un valor empieza vacío (`data: {}`), lo indica: su contenido llega al procesar una acción.
- Ctrl+clic en `state.catalog` abre el reducer; en `state.catalog.loading`, esa clave del estado inicial.
- Funciona con reducers de otros micro-frontends (alias de `jsconfig.json`); si el import no se resuelve, busca el
  reducer por nombre.
- **Forma real del estado:** lo que el backend llena (p. ej. `catalog.data`) se deduce de cómo se usa en todo el
  proyecto (`catalogState.data.entity.colorPrimary`, `const { data } = useSelector(…)`, `const { entity } = data`…).
  El hover lo muestra en *Usado en el código* y el autocompletado lo sugiere como *visto en el código*.
- También en variables que vienen de `useSelector` (`const catalogState = useSelector(…)` → `catalogState.data.│`) y
  con selectores definidos aparte (`useSelector(selectCatalog)`, funciones `select*` o `*Selector`).
- Cuando conoce las claves, el autocompletado muestra solo esas (sin la lista genérica `constructor`, `toString`…).

### Avisos (inspecciones) y correcciones rápidas (Alt+Enter)
| Aviso | Corrección |
|---|---|
| Prop usada sin declarar en `propTypes` | Agregar esa prop o todas las que faltan, deduciendo el tipo (valor por defecto o nombre: `is*` → bool, `handle*`/`on*` → func, `*Text` → string…). |
| Componente sin `propTypes` (intención sobre su nombre) | *Generar propTypes* con todas las props que usa, y el `import PropTypes` si falta. |
| Prop que el componente no acepta (`<Button text="Buscar">`), también en `Box`/`Typography`/`Grid`/`Stack` (acepta sus props de estilo: `m`, `px`, `bgcolor`, propiedades CSS…) | Cambiar por una prop de nombre parecido, usar el texto como contenido (children) o quitarla. |
| Valor desestructurado que el hook no devuelve | *Cambiar por 'x'* (el más parecido primero) y renombra sus usos. |
| Página o clave de traducción inexistente | — |
| Clave o página que falta en otro idioma (en el código y en `en.json`/`ki.json`) | Copiarla desde el idioma base, marcada con `[ES]`, respetando la indentación del archivo. |
| Texto `[ES] …` en `en.json`/`ki.json` | Señala lo que falta traducir. |
| Texto fijo en JSX (desactivado por defecto) | *Extraer a traducciones*. |

### Traducciones en el editor
- `getLanguagePage('│')`: autocompleta las páginas; Ctrl+clic abre la página en `es.json`.
- Ctrl+clic en una clave desestructurada (`const { title } = getLanguagePage('página')`) abre esa clave en `es.json`.
- Sigue la cadena completa hasta `getLanguagePage`: desestructuraciones anidadas (`conditions: { optionLabels }`),
  de segundo nivel (`const { buttons } = summary`) y accesos (`buttons.cancel`). En esos accesos, Ctrl+clic abre la
  clave en `es.json` (en vez de la lista de `cancel()` de las librerías) y el hover muestra el texto en cada idioma.
  Sobre una variable suelta (`buttons`), Ctrl+clic sigue yendo a su declaración.
- `const { │ } = getLanguagePage('página')`: autocompleta las claves con su texto. También con `|| {}` / `?? {}`.
- **Extraer a traducciones** (Alt+Enter sobre un texto fijo del JSX o una prop de texto como `title="…"`): pide la
  clave (sugerida a partir del texto), la agrega a la página en `es.json` y marcada `[ES]` en los demás idiomas,
  reemplaza el texto por la variable y la agrega a la desestructuración de `getLanguagePage`.
  - En componentes que aún no usan traducciones, primero pide la página (sugiere la de los archivos vecinos o la de la
    carpeta; se puede escribir una nueva y se crea en los JSON) y agrega `useTranslateContext()`, la llamada a
    `getLanguagePage` y el import que usa esa app.

### Comprobado que IntelliJ ya lo hace (no se duplicó)
- Aviso de prop requerida faltante con Alt+Enter (*Missing required attribute*, usa `propTypes`).
- Autocompletado de valores de `oneOf` y de lo que devuelve un hook al desestructurar.

## Pendiente

### Automatización del desarrollo (propuestas a partir del análisis de bancapersonas)
| Mejora | Para qué |
|---|---|
| Generador de flujo por pasos | Hay 33 flujos con la misma estructura (`Form`/`Confirm`/`OTP`/`Voucher` + `useStep`/`useForm`/`useOTP`/`useVoucher`/`useConfirm`). Crearlos desde un asistente: archivos, página de traducciones, exposición en webpack, mapeos en `jsconfig.json` y ruta en `mftheme`. |
| Exponer un módulo en Module Federation | Agregar `"./MFX": "./src/…"` al `webpack.config.js` del remote y el mapeo en todos los `jsconfig.json` que lo consumen (1196 mapeos mantenidos a mano; 6 archivos son copias idénticas). |
| Revisar que los `jsconfig.json` estén sincronizados | Avisar cuando una copia se desfasa o tiene mapeos que `webpack` ya no expone, con corrección para sincronizar. |
| Auto-import con el alias federado | Al usar un componente o hook de otro remote, importar `MFComponents/ComponentX` en vez de una ruta relativa (verificar antes qué hace IntelliJ). |
| Plantillas de código (live templates) | Bloques que se repiten: llamada a `fetchDataPromise` (202 usos) con manejo de `COD_OK`, `useSelector(state => state.catalog)`, desestructuración de `getLanguagePage`. |

### Otras
| Mejora | Para qué |
|---|---|
| Revisar `package.json` y `jsconfig.json` | Avisar versiones distintas de librerías compartidas por Module Federation y mapeos que `webpack` ya no expone. |
| Publicación | Push a GitHub y un workflow que genere el `.zip` en cada release (el build usa el IntelliJ local; en CI habría que descargarlo). |
| Probar en el IDE | Revisar en el popup real lo verificado con pruebas automáticas (Box con pnpm, traducciones, Alt+Enter). |

## Limitaciones conocidas
- Los tipos deducidos al generar `propTypes` son una aproximación (por nombre o valor por defecto); si no se puede
  deducir se usa `PropTypes.any`. Las props agregadas quedan opcionales (sin `.isRequired`).
- *Prop que el componente no acepta* solo revisa cuando conoce todas las props: en componentes propios, sin
  `...rest` ni `{...props}`; en MUI, si toda la herencia se resuelve y el tag no usa `component=`. Las props de MUI
  System salen de `csstype` y `@mui/system` (lista generada en `mui-system-props.txt`).
- Las traducciones copiadas llevan el texto del idioma base para traducirlo después.
- La ficha de hooks analiza `return { … }`, `return [ … ]`, `...spread` de objetos locales y `result.a = …`; si
  algún `return` o spread no se puede resolver, la lista se marca como incompleta y el aviso de hooks no se aplica.
- Redux: la forma de lo que llega del backend se deduce de los usos; una clave que nunca se lee en el código no aparece.

## Desarrollo

```bash
./gradlew test                       # pruebas (IntelliJ sin interfaz)
./gradlew buildPlugin                # genera el .zip
# Prueba de humo contra un proyecto real (opcional):
./gradlew test -PrealProject=/ruta/al/proyecto/apps -PrealMui=/ruta/a/node_modules
```

- Compila contra el IntelliJ instalado (`ideaPath` en `gradle.properties`) usando el JBR 25 del propio IDE.
- La documentación usa la API `platform.backend.documentation.psiTargetProvider`, que IntelliJ consulta antes que
  los `lang.documentationProvider` (el proveedor de React respondería primero).

## Historial
| Versión | Cambios |
|---|---|
| 1.9.0 | Redux: forma real deducida de los usos, variables y selectores aparte; MUI System revisado; extraer texto en componentes sin traducciones; autocompletado sin lista genérica. |
| 1.8.1 | Traducciones: Ctrl+clic y hover en accesos (`buttons.cancel`) y desestructuraciones anidadas o de segundo nivel. |
| 1.8.0 | Redux (`useSelector`: autocompletado, hover y Ctrl+clic); Ctrl+clic en claves de traducción; hooks con spread y resultado por partes; `Omit` en props heredadas. |
| 1.7.0 | Extraer texto a traducciones; aviso de prop que el componente no acepta; README. |
| 1.6.1 | Tipos y valores por defecto en props heredadas y atributos HTML. |
| 1.6.0 | Generar `propTypes`; Alt+Enter en el aviso de hooks; prefijo `[ES]`. |
| 1.5.x | Aviso de hooks; traducciones (autocompletado, hover, avisos, copiar desde el idioma base; `|| {}`). |
| 1.4.0 | Heredadas de ButtonBase y atributos HTML. |
| 1.3.0 | Hover por prop, Box, props heredadas, avisos de `propTypes`, ficha de hooks, ajustes. |
| 1.0–1.2 | Ficha de props (propTypes / desestructuración), MUI, firma resumida. |
