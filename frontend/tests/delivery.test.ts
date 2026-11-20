import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { courierOfTracking, matchStores, parseDelivery, parseTrackingLines } from '../src/parsing/delivery.ts'

// 用例与服务端的 DeliveryParser 共用，两边必须得到同样的结果。
const cases = JSON.parse(readFileSync(new URL('../../shared/parsing/delivery.json', import.meta.url), 'utf8'))

for (const c of cases.parse)
  test(`识别：${c.name}`, () => assert.deepEqual(parseDelivery(c.input, cases.couriers), c.expect))

test('按运单号开头推断快递公司', () => {
  for (const c of cases.courierOfTracking) assert.equal(courierOfTracking(c.input) ?? null, c.expect, c.input)
})

for (const c of cases.lines)
  test(c.name, () =>
    assert.deepEqual(
      parseTrackingLines(c.input, cases.couriers).map((l) => ({
        hint: l.hint,
        courier: l.courier ?? null,
        trackingNo: l.trackingNo ?? null,
        courierGuessed: !!l.courierGuessed,
      })),
      c.expect,
    ),
  )

test('对门店：名称片段、编号、合同号都能对上；对不上或有多个时不擅自选', () => {
  for (const c of cases.match)
    assert.deepEqual(
      matchStores(c.hint, cases.stores).map((s: { code: string }) => s.code),
      c.expect,
      c.hint,
    )
})
