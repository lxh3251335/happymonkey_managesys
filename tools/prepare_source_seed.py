"""Normalize the reviewed analysis detail, retaining cell-level provenance.

Original spreadsheets are never edited. Stock/unknown-brand rows are quarantined.
No inferred receipts, contracts, tax rates or supplier offers are produced.
"""
import json
import re
from collections import Counter
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path


def val(row, col):
    return next((v['value'] for k, v in row.items() if re.sub(r'\d', '', k) == col), '')


def number(value):
    try:
        return str(Decimal(value)) if value != '' else None
    except InvalidOperation:
        return None


def date(value):
    try:
        return (datetime(1899, 12, 30) + timedelta(days=float(value))).strftime('%Y-%m-%d')
    except (ValueError, OverflowError):
        return None


books = json.loads(Path('docs/source-workbooks.json').read_text(encoding='utf-8'))
detail = next(s for s in books[0]['sheets'] if s['name'] == '明细')
records, excluded, warnings = [], [], []
raw_lookup = {}
for sheet in books[1]['sheets']:
    for row in sheet['rows']:
        rownum = re.sub(r'\D', '', next(iter(row)))
        raw_lookup[f"{sheet['name']}!{rownum}"] = row
for row in detail['rows'][1:]:
    rn = re.sub(r'\D', '', next(iter(row)))
    entry = dict(customer=val(row, 'A'), brand=val(row, 'B'), contract=val(row, 'C'),
                 project=val(row, 'D'), orderDate=date(val(row, 'E')), shipping=val(row, 'F'),
                 name=val(row, 'G'), category=val(row, 'H'), specification=val(row, 'I'),
                 model=val(row, 'J'), quantity=number(val(row, 'K')),
                 cabinetCost=number(val(row, 'M')), accessoryCost=number(val(row, 'N')),
                 cost=number(val(row, 'O')), amount=number(val(row, 'P')),
                 unitPrice=number(val(row, 'Q')), unitCost=number(val(row, 'R')),
                 source=f"{books[0]['file']} / 明细!{rn}", rawSource=val(row, 'W'), note=val(row, 'X'))
    if entry['project'] in ('小象项目电箱备货', '快乐猴项目电箱备货', '欣阳通力备货项目', '温控器', '时控开关') or not entry['project']:
        entry['excludedReason'] = '批量备货或无明确门店归属的配件，待人工确认归属'
        excluded.append(entry)
        continue
    raw = raw_lookup.get(entry['rawSource'])
    if raw:
        for label, col in [('amount', 'P'), ('cost', 'O')]:
            a, b = entry[label], number(val(raw, col))
            if a is not None and b is not None and abs(Decimal(a) - Decimal(b)) > Decimal('0.02'):
                warnings.append(dict(source=entry['source'], rawSource=entry['rawSource'], field=label, analysis=a, original=b))
    if entry['cost'] is None or entry['amount'] is None:
        warnings.append(dict(source=entry['source'], issue='缺少成本或报价，利润保持不可计算'))
    if not entry['quantity'] or Decimal(entry['quantity']) <= 0:
        excluded.append(entry)
        continue
    records.append(entry)
summary = dict(records=len(records), projects=len({(r['customer'], r['project']) for r in records}),
               customers=len({r['customer'] for r in records}), excluded=len(excluded), warnings=len(warnings),
               brands=dict(Counter(r['brand'] for r in records)),
               amount=str(sum(Decimal(r['amount'] or '0') for r in records)),
               knownCost=str(sum(Decimal(r['cost'] or '0') for r in records)))
seed = dict(batch='source-20261114-v1', summary=summary, records=records,
            excluded=excluded, warnings=warnings,
            policy='以对比分析明细为历史快照；原始价格汇总用于核对；差异待复核。全部为历史报价，非已确认应收。税口径未知。')
Path('docs/source-seed.json').write_text(json.dumps(seed, ensure_ascii=False, indent=2), encoding='utf-8')
Path('docs/import-review.json').write_text(json.dumps(dict(summary=summary, warnings=warnings, excluded=excluded), ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False))
print(json.dumps(warnings[:8], ensure_ascii=False))
