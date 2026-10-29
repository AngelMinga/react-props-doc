#!/usr/bin/env python3
"""
Genera src/main/resources/sx-properties.tsv: las claves que acepta `sx` de MUI, con sus valores y notas.

Uso: python3 tools/generate_sx_data.py <ruta a csstype/index.d.ts> <ruta a @mui/system/styleFunctionSx/defaultSxConfig.js>

Columnas: nombre <TAB> valores (separados por " | ") <TAB> nota de MUI (vacía si no es un alias/clave del tema)
          <TAB> tipo: css | obsoleta | mui (solo atajo de MUI, no es CSS)
"""
import re
import sys

css_path, sx_config_path = sys.argv[1], sys.argv[2]
out_path = "src/main/resources/sx-properties.tsv"
css = open(css_path).read()

def block(name):
    m = re.search(r'export (?:interface|namespace) ' + name + r'\b[^\n]*\{\n', css)
    i, depth = m.end(), 1
    while depth:
        depth += {'{': 1, '}': -1}.get(css[i], 0)
        i += 1
    return css[m.end():i]

property_ns, datatype_ns = block('Property'), block('DataType')

def alias_body(namespace, name):
    m = re.search(r'(?:export )?type ' + name + r'(?:<[^>]*>)? =(.*?);\n', namespace, re.S)
    return m.group(1) if m else None

def values(type_expr, depth=0):
    """`Globals | "a" | DataType.X | (string & {})` → ["a", …, "string"] (sin los globales inherit/initial…)."""
    out = []
    for part in [p.strip() for p in type_expr.replace('\n', ' ').split('|')]:
        if not part or part == 'Globals':
            continue
        if part.startswith('"'):
            out.append(part.strip('"'))
        elif part in ('(string & {})', 'string'):
            out.append('string')
        elif part in ('(number & {})', 'number'):
            out.append('number')
        elif part.startswith('TLength'):
            out.append('número (px) o string')
        elif part.startswith('DataType.') and depth < 2:
            body = alias_body(datatype_ns, part.split('.')[1].split('<')[0])
            out.extend(values(body, depth + 1) if body else [part.split('.')[1]])
        else:
            out.append(re.sub(r'<.*', '', part.split('.')[-1]))
    seen = []
    for v in out:
        # Sin prefijos de navegador (-webkit-, -moz-, -ms-): no se usan en sx
        if v.startswith('-'):
            continue
        if v not in seen:
            seen.append(v)
    return seen

props = {}
kinds = {}
for iface in ['StandardLonghandProperties', 'StandardShorthandProperties', 'SvgProperties', 'ObsoleteProperties']:
    for name, type_ref in re.findall(r'^\s+(\w+)\?: ([^;]+?)(?: \| undefined)?;', block(iface), re.M):
        m = re.match(r'Property\.(\w+)', type_ref)
        body = alias_body(property_ns, m.group(1)) if m else type_ref
        props.setdefault(name, values(body) if body else [])
        kinds.setdefault(name, 'obsoleta' if iface == 'ObsoleteProperties' else 'css')

# Alias y claves del tema de MUI (defaultSxConfig)
sx = open(sx_config_path).read()
notes = {}
SPACING = {'padding': 'padding', 'margin': 'margin'}
for name, body in re.findall(r'^  (\w+): \{([^}]*)\}', sx, re.M):
    theme_key = re.search(r"themeKey: '([^']+)'", body)
    css_prop = re.search(r"cssProperty: '([^']+)'", body)
    style = re.search(r'style: _(\w+)\.(\w+)', body)
    transform = re.search(r'transform: _\w+\.(\w+)', body)
    parts = []
    if style and style.group(1) == 'spacing':
        parts.append(f"atajo de {style.group(2)}; usa theme.spacing (1 = 8px)")
    elif css_prop:
        parts.append(f"atajo de {css_prop.group(1)}")
    if theme_key:
        key = theme_key.group(1)
        typography_note = "variante del tema ('h4', 'body1'…)" if name == 'typography' else \
            f"también una clave de theme.typography (p. ej. '{name}Bold')" if name.startswith('font') else "clave de theme.typography"
        parts.append({'palette': "color del tema ('primary.main', 'text.secondary'…)",
                      'shape.borderRadius': 'multiplica theme.shape.borderRadius',
                      'typography': typography_note,
                      'zIndex': "clave de theme.zIndex ('modal', 'tooltip'…)",
                      'shadows': 'índice de theme.shadows (0–24)'}.get(key, f'theme.{key}'))
    if transform and transform.group(1) == 'sizingTransform':
        parts.append('0–1 = porcentaje, >1 = px')
    target = css_prop.group(1) if css_prop else (style.group(2) if style and style.group(1) != 'spacing' else None)
    if name not in props:
        props[name] = props.get(target, []) if target else ['número o string']
        kinds[name] = 'mui'
    elif style and style.group(1) == 'spacing':
        # Propiedad CSS real a la que MUI aplica el espaciado del tema (marginTop, paddingX…)
        parts = [f"en sx usa theme.spacing (1 = 8px)"]
    if parts:
        notes[name] = '; '.join(parts)

with open(out_path, 'w') as f:
    f.write('# Generado por tools/generate_sx_data.py desde csstype y @mui/system (defaultSxConfig)\n')
    for name in sorted(props):
        f.write(f"{name}\t{' | '.join(props[name])}\t{notes.get(name, '')}\t{kinds.get(name, 'mui')}\n")
print(len(props), 'claves;', len(notes), 'con nota de MUI →', out_path)
