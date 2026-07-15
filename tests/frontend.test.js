import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { MapAdapter, canTransition, formatStatus, validCoordinates } from "../frontend/app.js";

test("status labels and lifecycle rules stay aligned", () => {
  assert.equal(formatStatus("OUT_FOR_DELIVERY"), "Out for delivery");
  assert.equal(canTransition("ASSIGNED", "PICKED_UP"), true);
  assert.equal(canTransition("ASSIGNED", "DELIVERED"), false);
  assert.equal(canTransition("PICKED_UP", "CANCELLED"), false);
  assert.equal(canTransition("PLACED", "CONFIRMED"), true);
  assert.equal(canTransition("CONFIRMED", "PACKED"), true);
  assert.equal(canTransition("OUT_FOR_DELIVERY", "DELIVERY_VERIFICATION"), true);
  assert.equal(canTransition("DELIVERY_VERIFICATION", "DELIVERED"), true);
  assert.equal(canTransition("DELIVERED", "CANCELLED"), false);
  assert.equal(canTransition("SCHEDULED", "PLACED"), true);
});

test("marketplace, quote, gift and tracking controls are real shared behaviors", async () => {
  const source = await readFile(new URL("../frontend/app.js", import.meta.url), "utf8");
  assert.match(source, /\/pricing\/quote/);
  assert.match(source, /navigator\.geolocation\.watchPosition/);
  assert.match(source, /giftOptions/);
  assert.match(source, /Idempotency-Key/);
  assert.match(source, /optimisticAdd/);
  assert.match(source, /AbortController/);
  assert.match(source, /Current location/);
  assert.match(source, /patchCart/);
  assert.match(source, /admin\/orders\/\$\{id\}\/available-agents/);
  assert.match(source, /verify-delivery/);
  assert.match(source, /Share this code only when you receive your order/);
  assert.match(source, /modal-open/);
  assert.match(source, /setTimeout\(cleanup,400\)/);
  assert.match(source, /finally\{setButtonLoading/);
  assert.match(source, /data-demo-login="admin@relay\.demo"/);
  assert.doesNotMatch(source, /setInterval\s*\(/);
  assert.doesNotMatch(source, /location\.reload\s*\(/);
  assert.equal(typeof MapAdapter.create, "function");
});

test("coordinate validation handles bounds and non-numbers", () => {
  assert.equal(validCoordinates(12.97, 77.59), true);
  assert.equal(validCoordinates(91, 77), false);
  assert.equal(validCoordinates(12, Number.NaN), false);
});

test("all pages use the one shared stylesheet and module", async () => {
  for (const page of ["index", "customer", "agent", "admin"]) {
    const html = await readFile(new URL(`../frontend/${page}.html`, import.meta.url), "utf8");
    assert.match(html, /href="styles\.css"/);
    assert.match(html, /src="app\.js"/);
  }
});
