/**
 * StockLite 反馈通道反代（Vercel Edge Function）。
 *
 * 背景：大陆网络按 SNI 拦截 *.supabase.co（TCP 可连、TLS 握手被重置），插件直连必然失败。
 * 本函数用自有域名（如 https://feedback.fanshu.xyz/api）转发到同一个 Supabase 项目，
 * 数据、publishable key、RLS 策略、回复查询全部保持不变，只是出口域名换成自己的。
 *
 * 部署（Vercel 免费 Hobby 即可，DNS 无需迁出 DNSPod）：
 *   1. 把本目录（feedback-relay）作为独立项目推到 Git，Vercel 导入；
 *   2. 项目 Settings → Domains 添加 feedback.fanshu.xyz，按提示在 DNSPod 加一条
 *      CNAME：feedback → cname.vercel-dns.com；
 *   3. 证书自动签发后，把插件 FeedbackService.SUPABASE_RELAY_URL 填为
 *      https://feedback.fanshu.xyz/api（或临时用 -Dstocklite.feedback.baseUrl=... 验证）。
 *
 * 只放行 /api/rest/v1/feedback 这一个路径与三种方法，不能当通用代理使用；
 * 访问控制仍由 Supabase RLS + x-device-id 负责，与本函数无关。
 */

export const config = { runtime: "edge" };

const SUPABASE_URL = "https://wudikstpbcpfkbonmfxp.supabase.co";
const FORWARD_HEADERS = ["apikey", "x-device-id", "prefer", "content-type", "accept"];
const ALLOWED_METHODS = ["GET", "POST", "DELETE"];

export default async function handler(req: Request): Promise<Response> {
  if (!ALLOWED_METHODS.includes(req.method)) {
    return new Response("method not allowed", { status: 405 });
  }

  const headers = new Headers();
  for (const name of FORWARD_HEADERS) {
    const value = req.headers.get(name);
    if (value) headers.set(name, value);
  }

  const query = new URL(req.url).search;
  const body = req.method === "GET" ? undefined : await req.text();

  let upstream: Response;
  try {
    upstream = await fetch(`${SUPABASE_URL}/rest/v1/feedback${query}`, {
      method: req.method,
      headers,
      body,
    });
  } catch {
    return new Response("upstream unreachable", { status: 502 });
  }

  const outHeaders = new Headers();
  for (const name of ["content-type", "preference-applied"]) {
    const value = upstream.headers.get(name);
    if (value) outHeaders.set(name, value);
  }
  return new Response(await upstream.text(), { status: upstream.status, headers: outHeaders });
}
