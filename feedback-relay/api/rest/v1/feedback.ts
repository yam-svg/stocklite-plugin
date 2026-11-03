/**
 * StockLite 反馈通道反代（Vercel Edge Function）。
 *
 * 背景：大陆网络按 SNI 拦截 *.supabase.co（TCP 可连、TLS 握手被重置），插件直连必然失败。
 * 本函数用自有域名（如 https://feedback.fanshu.xyz/api）转发到同一个 Supabase 项目，
 * 数据、publishable key、RLS 策略、回复查询全部保持不变，只是出口域名换成自己的。
 *
 * 部署（Vercel 免费 Hobby 即可，DNS 无需迁出 DNSPod）。在本目录执行：
 *   1. npx vercel login && npx vercel --prod   （首次会问项目名，直接回车用默认值）
 *   2. npx vercel domains add feedback.example.com  或到 Dashboard → Settings → Domains 添加
 *   3. 在 DNSPod 给该子域加一条 CNAME → cname.vercel-dns.com（不要加 A 记录）
 *   4. 证书签发后验证：
 *      curl -s -o /dev/null -w '%{http_code}\n' \
 *        -H 'apikey: sb_publishable_CBWJFeu3z8XtT2koZ-mvCw_MseWTReV' \
 *        -H 'x-device-id: 00000000-0000-0000-0000-000000000000' \
 *        'https://feedback.example.com/api/rest/v1/feedback?select=id&limit=1'
 *      返回 200（空数组 []）即通；403 说明 anon 角色缺少 select 权限，需要检查 RLS 策略。
 *   5. 把插件 FeedbackService.SUPABASE_RELAY_URL 填为
 *      https://feedback.example.com/api（或临时用 -Dstocklite.feedback.baseUrl=... 验证）。
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
  const body = req.method === "GET" ? undefined : (await req.text()) || undefined;

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

  const outHeaders = new Headers({ "cache-control": "no-store" });
  for (const name of ["content-type", "preference-applied"]) {
    const value = upstream.headers.get(name);
    if (value) outHeaders.set(name, value);
  }
  return new Response(await upstream.text(), { status: upstream.status, headers: outHeaders });
}
