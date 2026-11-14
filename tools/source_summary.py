import json

books = json.load(open('docs/source-workbooks.json', encoding='utf-8'))
for book in books:
    print(book['file'], [(s['name'], len(s['rows'])) for s in book['sheets']])
for name in ['明细', '门店报价']:
    sheet = next(s for s in books[0]['sheets'] if s['name'] == name)
    print(name)
    print(json.dumps([{k: v['value'] for k, v in r.items()} for r in sheet['rows'][:7]], ensure_ascii=False))
