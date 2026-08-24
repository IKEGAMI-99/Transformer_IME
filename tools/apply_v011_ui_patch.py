from pathlib import Path

path = Path('app/src/main/java/com/ikegami/transformerime/ime/TransformerImeService.kt')
text = path.read_text(encoding='utf-8')
replacements = {
    'functionButton("あa1", pill = true) { toggleMode(false) }': 'functionButton("あa1") { toggleMode(false) }',
    'functionButton("↵", accent = true) { handleEnter() }': 'functionButton("↵") { handleEnter() }',
    'numberPadButton(label, accent = true) { handleEnter() }': 'numberPadButton(label) { handleEnter() }',
    'qwertyButton("↵", 1.2f, accent = true) { handleEnter() }': 'qwertyButton("↵", 1.2f) { handleEnter() }',
}
changed = 0
for old, new in replacements.items():
    count = text.count(old)
    if count:
        text = text.replace(old, new)
        changed += count

if changed:
    path.write_text(text, encoding='utf-8')
    print(f'patched {changed} UI call sites')
else:
    print('v0.11 UI patch already applied')
