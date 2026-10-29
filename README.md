# React Props Doc

Plugin para IntelliJ IDEA que muestra y revisa las props de componentes React sin tener que escribir JSDoc, y
ayuda con las traducciones de páginas (`getLanguagePage('página')`). Nació para el monorepo *bancapersonas*
(React + Module Federation + MUI), pero funciona en cualquier proyecto React con `propTypes` o MUI.

- **IDE:** IntelliJ IDEA Ultimate 2026.3 o superior (requiere los plugins JavaScript y JSON incluidos).
- **Versión actual:** 1.8.1

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

### Avisos (inspecciones) y correcciones rápidas (Alt+Enter)
| Aviso | Corrección |
|---|---|
| Prop usada sin declarar en `propTypes` | Agregar esa prop o todas las que faltan, deduciendo el tipo (valor por defecto o nombre: `is*` → bool, `handle*`/`on*` → func, `*Text` → string…). |
| Componente sin `propTypes` (intención sobre su nombre) | *Generar propTypes* con todas las props que usa, y el `import PropTypes` si falta. |
| Prop que el componente no acepta (`<Button text="Buscar">`) | Cambiar por una prop de nombre parecido, usar el texto como contenido (children) o quitarla. |
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

### Comprobado que IntelliJ ya lo hace (no se duplicó)
- Aviso de prop requerida faltante con Alt+Enter (*Missing required attribute*, usa `propTypes`).
- Autocompletado de valores de `oneOf` y de lo que devuelve un hook al desestructurar.

## Pendiente

| Mejora | Para qué |
|---|---|
| Revisar `package.json` y `jsconfig.json` | Avisar versiones distintas de librerías compartidas por Module Federation y mapeos que `webpack` ya no expone. |
| Redux: contenido real de `data` | Hoy se muestra el estado inicial (`data: {}`); la forma real llega del backend. Podría inferirse de los usos en el código (`catalog.data.entity…`). |
| Redux: `useSelector` con selectores definidos aparte (`useSelector(selectCatalog)`) | Hoy solo funciona con la función en línea (`state => state.x`). |
| Revisar props de componentes con MUI System | Hoy `Box`, `Typography`, `Grid` y `Stack` no se revisan en *Prop que el componente no acepta*. |
| Extraer texto en componentes sin `getLanguagePage` | Agregar también el `useTranslateContext()` y la llamada. |
| Publicación | Push a GitHub y un workflow que genere el `.zip` en cada release (el build usa el IntelliJ local; en CI habría que descargarlo). |
| Probar en el IDE | Revisar en el popup real lo verificado con pruebas automáticas (Box con pnpm, traducciones, Alt+Enter). |

## Limitaciones conocidas
- Los tipos deducidos al generar `propTypes` son una aproximación (por nombre o valor por defecto); si no se puede
  deducir se usa `PropTypes.any`. Las props agregadas quedan opcionales (sin `.isRequired`).
- *Prop que el componente no acepta* solo revisa cuando conoce todas las props: en componentes propios, sin
  `...rest` ni `{...props}`; en MUI, si toda la herencia se resuelve y el tag no usa `component=`.
- Las traducciones copiadas llevan el texto del idioma base para traducirlo después.
- La ficha de hooks analiza `return { … }`, `return [ … ]`, `...spread` de objetos locales y `result.a = …`; si
  algún `return` o spread no se puede resolver, la lista se marca como incompleta y el aviso de hooks no se aplica.
- Redux muestra el estado **inicial**: lo que el backend llena después (p. ej. `catalog.data`) no se conoce desde el código.

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
| 1.8.1 | Traducciones: Ctrl+clic y hover en accesos (`buttons.cancel`) y desestructuraciones anidadas o de segundo nivel. |
| 1.8.0 | Redux (`useSelector`: autocompletado, hover y Ctrl+clic); Ctrl+clic en claves de traducción; hooks con spread y resultado por partes; `Omit` en props heredadas. |
| 1.7.0 | Extraer texto a traducciones; aviso de prop que el componente no acepta; README. |
| 1.6.1 | Tipos y valores por defecto en props heredadas y atributos HTML. |
| 1.6.0 | Generar `propTypes`; Alt+Enter en el aviso de hooks; prefijo `[ES]`. |
| 1.5.x | Aviso de hooks; traducciones (autocompletado, hover, avisos, copiar desde el idioma base; `|| {}`). |
| 1.4.0 | Heredadas de ButtonBase y atributos HTML. |
| 1.3.0 | Hover por prop, Box, props heredadas, avisos de `propTypes`, ficha de hooks, ajustes. |
| 1.0–1.2 | Ficha de props (propTypes / desestructuración), MUI, firma resumida. |
