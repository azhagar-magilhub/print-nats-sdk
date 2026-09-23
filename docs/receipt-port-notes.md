# Receipt payload port — notes

`ReceiptPayloadBuilder` produces the object MerchantApp hands to native
`PrintFramework.printReceiptJson(JSON.stringify(optimizedJsonData), isTextReceiptPrint)`.

```java
ReceiptPayloadBuilder.Result r = new ReceiptPayloadBuilder(printDates /*, optional Clock */)
        .build(orderJson, new Restaurant(restaurantDetailsJson), services);
r.payload;         // JsonObject: exactly optimizeReceiptData(updatedOrderDetails)
r.json();          // exactly JSON.stringify(...) of it (JS number digits, no HTML escaping)
r.textReceipt;     // isTextReceiptPrint
r.openCashDrawer;  // always false (see Services)
```

Files: `core/src/main/java/com/magilhub/printnats/rules/ReceiptPayloadBuilder.java` and
`rules/receipt/{Js, ReceiptOptimizer, ReceiptServices, RefundedFee}.java`.
Tests: `core/src/test/java/com/magilhub/printnats/rules/ReceiptPayloadBuilderTest.java`.

## Source baseline — read this first

The brief said Release-25.1, but the requested behaviour (refunded fee block / status 109, 26+32 refund lookup in
both transaction arrays, the pay-QR paid/refund vetoes, the refund relabel in optimizeReceiptData) exists **only in
the MerchantApp working tree**, which is on **`Release-27.4` (HEAD `ecf6b814c`, clean)**. `Release-25.1` lacks
~215 lines of `useNetworkPrintService.tsx` and ~140 of `optimizeReceiptData-utils.ts`. The port follows the
on-disk Release-27.4 files; line numbers below are for those. `KotPayloadBuilder`/`parity-matrix.md` are still on
Release-25.1 — the two baselines differ.

Call chain: `useOrderPrintService.tsx printReceipt` (374–455) → `groupOrderItems` (helpers/cartSummary.helper.ts:24,
an identity function — body commented out) → `useNetworkPrintService.tsx printNetworkReceipt` (599–1144) →
`optimizeReceiptData` (optimizeReceiptData-utils.ts:292–776).

## Field table

`U` = useNetworkPrintService.tsx, `O` = optimizeReceiptData-utils.ts, `P` = useOrderPrintService.tsx.
"passthrough" = copied from the order by `{...orderDetails}` and then by O's whitelist (absent → key dropped).
Java: `B` = ReceiptPayloadBuilder, `RO` = ReceiptOptimizer.

| Payload field | JS source | Java |
|---|---|---|
| `businessDetails.name` / `currentLocation` | U:1039 / U:1049 — `branchName.split(',')[0]` / `[1]` (absent when no comma) | B.build (`branchParts`) |
| `businessDetails.logo` | U:256 getImageURL('LOGO') — `REACT_APP_IMAGE_URL + mime[0] + '/' + id + '.' + mime[1]`; '' when no media | B.imageUrl |
| `businessDetails.caption` | U:1041 — `gstNo` if non-empty else '' | B.build |
| `businessDetails.email/address/contactNumber/website` | U:1045–1048 (`phoneNumber`, website '') | B.build |
| `businessDetails.country` | O:567 ← U:1074 `restaurantDetails.country` | RO.optimize |
| `openCashDrawer`, `orderNo`, `serverStaffName`, `tableName`, `comment`, `orderSourceName`, `isAutoPrint`, `isScheduled`, `isOrderCancelled` | passthrough, O:570–587 | RO.optimize |
| `orderDate` / `orderTime` | U:1055–1056 — **print time** `format(new Date())`, device zone, `MM/dd/yyyy` / `hh:mm a` | B.build (`now`) |
| `etaTime` | U:1012,1059 — orderDate's date + orderTime's time, `MM/dd/yyyy hh:mm a`, device zone; null when either missing | B.etaTime |
| `cardType` | U:1057 — `paymentStatus.cardType ‖ JSON.parse(paymentStatus.response).cardType ‖ ''` | B.cardType |
| `cardInfo` | U:1058 | B.build |
| `fullName` / `phone` | U:1015–1016 — value if truthy else '' | B.build |
| `orderTypeGroup` | U:1020 — 'SALE' / getOrderTypeGroupNamePrinterV1 (uses the order's `isEventOrder` field) / getOrderTypeGroupNamePrinter / '-' | B.orderTypeName |
| `items` | U:652 applyOriginalPrice → O:590 projection (+`isFreeItem`, `redeemPoint`) | B.applyOriginalPrice, RO.items |
| `refundedItems` | U:1103 (gated `disableRefundItemsPrint === true`) → O:611 (with isFreeItem/redeemPoint) | same |
| `voidedItems` / `refundItems` | U:1093 / U:1095 (gated `disableVoidItemsPrint` / `disableRefundItemsPrint`) → O:632–633 mapReceiptItem (no isFreeItem/redeemPoint) | same |
| `refundedAmount` | O:645 — `refundedAmount?.toFixed(2) ‖ refundAmount?.toFixed(2) ‖ "0.00"`, "0.00" when within 0.005 of `refundedFee.totalAmount` | RO.refundedAmount |
| `refundedFee` | U:73 buildRefundedFeeReceipt, U:1110 gate → O:657 | RefundedFee.build |
| `totals` | U:843–1002 (IN dine-in split / ≥0.01 filter + surcharge / discount-per-offer) → O:482 `{title,value,code}` + O:520 T.Receipt override | B.indiaDineInTotals, B.discountPerOffer, RO.optimize |
| `paymentType` | U:1006 + 1017 — `tenderType + ' - ' + amountTendered + ' '` per txn, IN only | B.build |
| `paymentlink` | U:1024 — UPI link, IN + `paymentProvider.classData.userName` | B.paymentLink |
| `paymentStatus` | O:665 — projection of the order's paymentStatus, `statusCode`/`amountTendered` from the refund relabel (O:471), `response`/`request` stringified, `createdTime`/`modifiedTime` via convertUTCStringToLocal (O:362); null when split | RO.paymentStatus |
| `footer.line1` | U:1051 `receiptFooter ?? ''` → O:693 | B.build |
| `transactions` | U:1068 (T.Receipt: drop 25) / U:544 getTransactionData (26 → 32 → 24/19/61) → O:698; null when split | B.transactionData, RO.transactions |
| `paymentInfo` | O:718 getPaymentInfo (O:295) / getTransactionPaymentInfo (O:330); null when split | RO.paymentInfo / transactionPaymentInfo |
| `isCustomizationCountRequired` | U:1060 `restaurantDetails.customizationCountRequired` | B.build |
| `transactionStatusCode` | U:1073 — first getTransactionData row's statusCode, else "0" | B.build |
| `showKotNumber` / `showReceiptNo` / `showRestaurantName` | U:1075–1077 (defaults "false"/"true"/"true") → O:727 `.toString()` | B.build |
| `kotNo` | U:1085 → O:729 `.toString()` | B.build |
| `reviewQRLink` / `reviewMessage` | U:1078–1079 | B.build |
| `payQrLink` / `cards` | U:694–834, 1083–1084 → O:733–734 | B.build |
| `isSalesOrder` | U:1086 | B.build |
| `eodTipConfig` | U:611–622 (`percents`, `fixedAmounts` via getSuggestedTips U:591, `currencySymbol` "$", `isEodTipEnabled`, `isTransactionReceipt`) | B.build, B.suggestedTips |
| `loyalty_point_receipt` | U:630 loyalty call → O:737 projection; `points_name` = `restaurantDetails.pointsName ?? …points_name ?? null` | RO.loyalty |
| **isTextReceiptPrint** | U:1141–1142 `isDataCapDevice() ‖ uiFeatureFlags.enableTextBasedReceiptPrint` (JS truthiness: the string "false" counts as true) | Result.textReceipt |

Discount lines (U:937–1002): offers grouped by `offerId ‖ offerName` (summed `Number(offerAmount) ‖ 0`), label
`(name[ - code])`; `(Custom discount)` = the code-6 "Discount" total only when there are offers AND no
order-level offer (`isItemLevel === false`); the first "Discount"/"Item Level Discount" row is replaced by the lines
(first title prefixed `Discount\n`, values `($x.xx)` / `(₹x.xx)` — `$` only for country US), later ones dropped.

India dine-in (U:843–903, `typeGroup D && country IN && (itemTax ‖ serviceTax)`): keep totals ≥ 0.1 except
Grand Total/Tax/Service Tax/Service Charge; add `SC @<orderTax.rate>%` (dine-in order type's `orderTax`), `CGST @<defaultTax.rate/2>%`
and `SGST @…%` (itemTax/2 each), then `Grand Total` from the `code == '5.0'` row. Codes '6','5','4','7'.

## Services (`ReceiptServices`) — when JS calls them

| Method | JS | When | Populates |
|---|---|---|---|
| `loyaltyOrderPointReceipt(orderId)` | `loyaltyOrderPointReceipt` GET `/api/v1/loyalty/orders/{id}/point-receipt` (U:632) | every receipt with a truthy `orderId`; result used only when `data.success`; throw → null | `loyalty_point_receipt` |
| `payQrUrl(order, restaurant)` | `getReceiptPayQrUrl` (features/order/receiptPayQr.ts) (U:827) | only when the gate passes: not paid (19/24 loose, `isTransactionCompleted === true`, any 19/24/61 in either array, transaction-based receipt), no 26/32 row in `transactions`, not split, grand total > 0, not cancelled (`isOrderCancelled === true` or status '9'), not `isSalesOrder`. The helper itself checks `generatePayQr === true`, never throws, '' = skip | `payQrLink`, and `cards` (`restaurantDetails.cards` only when the link is non-empty) |
| `isDataCapDevice()` | utils/device-utils.ts — `DeviceInfo.getBrand()` = 'pax' | every receipt, after the payload is built | `Result.textReceipt` |
| `cardProcessingSurcharge(key)` | Redux `state.payment.cpSurchargeByOrder[`${orderId}:${splitId ‖ ''}`]` (U:916) | non-(IN dine-in) totals path only | "Card Processing Fee" row (code 9.0, sortOrder 8) unless a `code === '9.0'` / "card processing fee" row exists |
| `imageBaseUrl()` | `Config.REACT_APP_IMAGE_URL ‖ ''` (U:263) | when `restaurantDetails.media` is non-empty | `businessDetails.logo` |

Not modelled (no payload effect): `ToastAndroid.show('Print initiated!')`, `PrintFramework.appendToLogFile` (the
`[payqr-gate]` debug lines and the optimized-data log). **Cash drawer:** `triggerCashDrawer()` /
`PrintFramework.openCashDrawer()` are commented out in `printNetworkReceipt` (U:1138–1139), so
`Result.openCashDrawer` is always false. The payload's own `openCashDrawer` key is only the order's field passed
through (normally absent). Also: `restaurantOrderTypes`, `defaultTax` and the logo come from Redux
`currentRestaurantDetail`, which is the same object `printReceipt` passes in, so the builder reads them all from `restaurant`.

## Deviations (places where JS would throw and abort the print)

| # | JS behaviour | Port |
|---|---|---|
| D1 | TypeError on missing/odd data: `orderDetails.transactions.map` (no transactions), `orderDetails.items.map`, `orderDetails.totals.find`, `restaurantDetails.branchName.split` (null), `media` with no LOGO entry, `paymentProvider.classData.userName` without classData, `transactions[0]` in isEodTipEnabled with no transactions, non-array `.map`s | treated as empty / '' / false |
| D2 | `JSON.parse` of a bad `paymentStatus.response`/`request` string (cardType, getPaymentInfo) throws; an object `response` in `cardType` becomes `JSON.parse("[object Object]")` and throws | cardType → ''; getPaymentInfo uses `undefined` for the unparseable part (lines read "Merchant Id: undefined", ...) |
| D3 | `format(new Date(mergedUTC))` throws RangeError when orderDate/orderTime make an invalid date (e.g. orderTime without a 'T') | `etaTime` = null |
| D4 | `refundedAmount.toFixed` / `transaction.amountTendered.toFixed` throw when the value is a string (or missing, for the latter) | `Number(x).toFixed(2)` ("NaN" when missing) |
| D5 | getSuggestedTips: a **string** `amountTendered` makes `orderTotal + tipAmount` a string concatenation and `.toFixed` throws | the amount is converted with `Number()` first |
| D6 | `tipPercentageConfig` that is a length-4 **string** is accepted by `.length == 4` and then `.map` throws | only a 4-element array is accepted; anything else → [12,15,18,22] |
| D7 | a `null` entry in an items/options list makes the projection throw | projected as `{}` (all fields absent) |

`Js.toFixed` rounds the exact binary value half-away-from-zero, which is what JS does (1.005 → "1.00",
10.01/2 → "5.00", -0.001 → "-0.00"). `String.format("%.2f")` would differ, so the port doesn't use it anywhere.

## Things I was unsure about

- **Branch** (see above). If the target really is Release-25.1, remove: RefundedFee + `refundedFee`, the O relabel
  and the `capturedTenderCount` split rule (25.1 uses `transactions.length > 1`), the "Refund:" label, the 26-in-both-arrays
  lookup, the pay-QR `isOrderPaid`/refund vetoes, and `(Config.REACT_APP_IMAGE_URL)` (25.1 hard-codes
  `https://static.magilhub.com/`).
- **`convertUTCStringToLocal` / date-fns parse**: modelled as `MM`/`dd`/`hh`/`mm` = 1–2 digits, `yyyy` = 1–4, `a` =
  `[ap]\.?\s?m\.?`, trailing whitespace allowed. The JS `(AM|PM)$ → " $1"` rewrite turns an already well-formed
  `"12/09/2025 01:50 AM"` into a double space, which date-fns rejects, and the ISO fallback then fails → `""`. The port
  does the same (tested). The ISO fallback accepts `YYYY-MM-DDTHH:mm[:ss[.fff]]Z` only. Hermes' `new Date()` fallback
  parser for non-ISO strings isn't modelled.
- **Number formatting**: `Js.numberToString` builds on `Double.toString`, which before JDK 19 is not always the
  *shortest* round-trip form (a known JDK bug, rare). Integers > 2^53 in the input lose precision exactly as they
  would in `JSON.parse`, because every number is re-expressed as a double (this is intentional).
  `JSON.stringify` escaping is matched: no HTML escaping, lone surrogates escaped; Gson's ` ` escaping is avoided.
- **`isTextReceiptPrint`** in JS can be a non-boolean (e.g. the string "false") when it reaches the RN bridge; the
  port reports JS truthiness. It's unclear what the bridge did with a non-boolean.
- `orderTypeGroup` in `isEodTipEnabled` reads the **order's raw** `orderTypeGroup` field, not the restaurant
  lookup. Ported as-is.
- `getOrderTypeGroup` matches ids with `==`; `Restaurant.orderTypeGroup` compares the string forms (same result for
  string/number ids).
- `printReceipt` also computes `receiptOfferRows`, but only the non-network `RNPrint` HTML path uses it; it isn't ported.
