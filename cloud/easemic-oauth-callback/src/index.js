const CALLBACK_PATH = "/cf/callback";
const APP_CALLBACK = "com.easemic.app://cf/oauth/callback";

export function handleRequest(request) {
  const url = new URL(request.url);
  if (url.pathname !== CALLBACK_PATH) {
    return new Response(null, { status: 404 });
  }

  const error = url.searchParams.get("error");
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const errorDescription = url.searchParams.get("error_description");

  if (error) {
    const bounce = new URLSearchParams();
    bounce.set("error", error);
    if (errorDescription) bounce.set("error_description", errorDescription);
    if (state) bounce.set("state", state);
    return new Response(null, {
      status: 302,
      headers: { Location: `${APP_CALLBACK}?${bounce.toString()}` },
    });
  }

  if (!code || !state) {
    return new Response("missing code or state", { status: 400 });
  }

  const bounce = new URLSearchParams();
  bounce.set("code", code);
  bounce.set("state", state);
  return new Response(null, {
    status: 302,
    headers: { Location: `${APP_CALLBACK}?${bounce.toString()}` },
  });
}

export default {
  fetch(request) {
    return handleRequest(request);
  },
};
