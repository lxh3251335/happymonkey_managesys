import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { matchCustomer, parseReceipt } from '../src/parsing/receipt.ts'

// 用例与服务端的 ReceiptParser 共用，两边必须得到同样的结果。
const cases = JSON.parse(readFileSync(new URL('../../shared/parsing/receipt.json', import.meta.url), 'utf8'))
const [y, m, d] = cases.today.split('-').map(Number)
const TODAY = new Date(y, m - 1, d)

for (const c of cases.parse) test(`识别：${c.name}`, () => assert.deepEqual(parseReceipt(c.input, TODAY), c.expect))

test('对客户：全称里包含简称；多个对上取最长；对不上不猜', () => {
  for (const c of cases.match) assert.equal(matchCustomer(c.text, cases.customers)?.id ?? null, c.expect, c.text)
})
