import { test } from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "./src/index.js";

const APP = "com.easemic.app://cf/oauth/callback";

function request(method, pathAndQuery) {
  return new Request(`https://oauth.easemic.com${pathAndQuery}`, {
    method,
  });
}

test("GET /cf/callback without params returns 400", async () => {
  const res = await handleRequest(request("GET", "/cf/callback"));
  assert.equal(res.status, 400);
  assert.equal(res.headers.get("location"), null);
  const body = await res.text();
  assert.match(body, /missing code or state/i);
});

test("GET /cf/callback with code but no state returns 400", async () => {
  const res = await handleRequest(request("GET", "/cf/callback?code=abc"));
  assert.equal(res.status, 400);
  assert.equal(res.headers.get("location"), null);
});

test("GET /cf/callback with code and state returns 302 to the app", async () => {
  const res = await handleRequest(
    request("GET", "/cf/callback?code=abc&state=xyz"),
  );
  assert.equal(res.status, 302);
  assert.equal(res.headers.get("location"), `${APP}?code=abc&state=xyz`);
});

test("GET /other returns 404", async () => {
  const res = await handleRequest(request("GET", "/other"));
  assert.equal(res.status, 404);
});

test("GET /cf/callback drops extra query params from Location", async () => {
  const res = await handleRequest(
    request("GET", "/cf/callback?code=abc&state=xyz&extra=nope"),
  );
  assert.equal(res.status, 302);
  const location = res.headers.get("location");
  assert.equal(location, `${APP}?code=abc&state=xyz`);
  assert.ok(!location.includes("extra"));
});

test("HEAD /cf/callback without params returns 400", async () => {
  const res = await handleRequest(request("HEAD", "/cf/callback"));
  assert.equal(res.status, 400);
  assert.equal(res.headers.get("location"), null);
});

test("GET /cf/callback with error= bounces to the app", async () => {
  const res = await handleRequest(
    request(
      "GET",
      "/cf/callback?error=access_denied&error_description=The%20user%20denied%20consent&state=xyz",
    ),
  );
  assert.equal(res.status, 302);
  const location = res.headers.get("location");
  assert.equal(
    location,
    `${APP}?error=access_denied&error_description=The+user+denied+consent&state=xyz`,
  );
});

test("GET /cf/callback with error= and no state still bounces", async () => {
  const res = await handleRequest(
    request("GET", "/cf/callback?error=access_denied"),
  );
  assert.equal(res.status, 302);
  assert.equal(res.headers.get("location"), `${APP}?error=access_denied`);
});

