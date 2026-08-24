"use client";

import { useEffect, useMemo, useState } from "react";
import dynamic from "next/dynamic";
import { AppIcon } from "./components/app-icon";

type View = "home" | "approvals" | "revenue-intelligence" | "modules" | "resources" | "users" | "organizations" | "access" | "activity" | "files" | "settings";
type AuthStep = "login" | "mfa";

class ApiProblemError extends Error {
  constructor(message: string, readonly code?: string) { super(message); }
}
const API_URL = process.env.NEXT_PUBLIC_CORE_API_URL ?? "https://api.corejava.sgodata.com";
const DemoApprovalWorkspace = dynamic(() => import("./demo/approval-workspace"), { ssr: false });
const RevenueIntelligenceWorkspace = dynamic(() => import("./modules/revenue-intelligence"), { ssr: false });
type UserInfo = { id: string; email: string; displayName: string; role: string };
type NavigationItem = { key:string; parentKey:string; ownerModule:string; label:string; labelKey:string; icon:string; type:"GROUP"|"PAGE"; viewKey:string; route:string; sortOrder:number; keywords:string[] };
type NavigationSection = { key:string; label:string; labelKey:string; icon:string; sortOrder:number; items:NavigationItem[] };
type NavigationModel = { revision:string; sections:NavigationSection[]; favoriteKeys:string[]; recentKeys:string[] };
type ModuleItem = { id: string; name: string; moduleKey: string; version: string; status: string; description: string; metric: string };
type ResourceItem = { id: string; name: string; storageMode: string; ownerModule: string; records: number; schemaVersion: string; updatedAt: string };
type ActivityItem = { id: string; kind: string; name: string; metadata: string; status: string; occurredAt: string };
type RoleItem = { id: string; name: string; users: number; policies: number; scope: string };
type FileItem = { id: string; name: string; mediaType: string; sizeBytes: number; classification: string; status: string; updatedAt: string };
type AuditItem = { id: string; actorEmail: string; action: string; resourceType?: string; resourceId?: string; result: string; correlationId: string; occurredAt: string };
type DynamicDefinition = { id:string; resourceKey:string; name:string; version:number; schema:{fields:Array<{key:string;type:string;required?:boolean}>}; status:string; updatedAt:string };
type AccessUser = { id:string; email:string; displayName:string; enabled:boolean; roles:string[]; createdAt:string };
type AccessRole = { id:string; code:string; name:string; systemRole:boolean };
type AccessPolicy = { id:string; code:string; resourceType:string; action:string; effect:string; condition:string; version:number; enabled:boolean };
type Organization = { id:string; code:string; name:string; parentCode?:string; status:string; createdAt:string };
type SessionData = { accessToken:string; refreshToken:string; expiresAt:string; user:UserInfo };
type JobItem = { id:string; tenantKey:string; jobType:string; status:string; attempts:number; leasedBy?:string; availableAt?:string; lastError?:string; createdAt:string };
type OutboxItem = { id:string; eventType:string; tenantKey:string; status:string; attempts:number; createdAt:string; availableAt:string; lastError?:string };
type BootstrapData = {
  summary: { resources: number; modules: number; pendingOutbox: number; runningJobs: number; files: number; storageGb: number; coreVersion: string; environment: string };
  modules: ModuleItem[]; resources: ResourceItem[]; activities: ActivityItem[]; roles: RoleItem[]; files: FileItem[]; audit: AuditItem[]; settings: Record<string,string>;
};

const MODULE_ICONS: Record<string,string> = {
  "audit-store":"shield", "event-outbox":"webhook", "local-identity":"users", "job-queue":"clock",
  kernel:"cpu", permission:"lock", webhook:"webhook", "dynamic-resource":"database",
  "file-management":"files", "control-plane":"sliders", "approval-domain":"clipboard-check", "revenue-intelligence":"chart",
};

function storedToken() {
  return typeof window === "undefined" ? "" : window.localStorage.getItem("core-access-token") || window.sessionStorage.getItem("core-access-token") || "";
}

function storedRefreshToken() {
  return typeof window === "undefined" ? "" : window.localStorage.getItem("core-refresh-token") || window.sessionStorage.getItem("core-refresh-token") || "";
}

function persistSession(session: SessionData, remember: boolean) {
  const target = remember ? window.localStorage : window.sessionStorage;
  const other = remember ? window.sessionStorage : window.localStorage;
  other.removeItem("core-access-token"); other.removeItem("core-refresh-token");
  target.setItem("core-access-token", session.accessToken); target.setItem("core-refresh-token", session.refreshToken);
}

function clearSession() {
  window.localStorage.removeItem("core-access-token"); window.localStorage.removeItem("core-refresh-token");
  window.sessionStorage.removeItem("core-access-token"); window.sessionStorage.removeItem("core-refresh-token");
}

let sessionRefresh: Promise<boolean> | null = null;
async function refreshSession(): Promise<boolean> {
  if (sessionRefresh) return sessionRefresh;
  const refreshToken = storedRefreshToken();
  if (!refreshToken) return false;
  sessionRefresh = fetch(`${API_URL}/api/v1/auth/refresh`, { method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({refreshToken}) })
    .then(async response => {
      if (!response.ok) { clearSession(); return false; }
      const session = await response.json() as SessionData;
      persistSession(session, Boolean(window.localStorage.getItem("core-refresh-token")));
      return true;
    })
    .catch(() => { clearSession(); return false; })
    .finally(() => { sessionRefresh = null; });
  return sessionRefresh;
}

async function apiRequest<T>(path:string, init:RequestInit = {}):Promise<T> {
  const request = () => fetch(`${API_URL}${path}`, {
    ...init,
    headers: { Authorization:`Bearer ${storedToken()}`, ...(init.body ? {"Content-Type":"application/json"} : {}), ...init.headers }
  });
  let response = await request();
  if (response.status === 401 && path !== "/api/v1/auth/refresh" && await refreshSession()) response = await request();
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiProblemError(problem.detail || "Thao tác không thành công.", problem.code);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

function LoginScreen({ onAuthenticated }: { onAuthenticated: (session: SessionData, remember: boolean) => void }) {
  const [step, setStep] = useState<AuthStep>("login");
  const [email, setEmail] = useState("admin@core.local");
  const [password, setPassword] = useState("");
  const [remember, setRemember] = useState(true);
  const [otp, setOtp] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [challengeId, setChallengeId] = useState("");

  const submitLogin = async (event: React.FormEvent) => {
    event.preventDefault();
    setError("");
    if (!/^\S+@\S+\.\S+$/.test(email)) return setError("Vui lòng nhập địa chỉ email hợp lệ.");
    if (password.length < 8) return setError("Mật khẩu phải có ít nhất 8 ký tự.");
    setLoading(true);
    try {
      const response = await fetch(`${API_URL}/api/v1/auth/login`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email, password, remember }) });
      const body = await response.json().catch(() => ({ detail: response.statusText || "Backend trả phản hồi không hợp lệ." }));
      if (!response.ok) throw new Error(body.detail ?? "Không thể đăng nhập.");
      if (body.mfaRequired === false && body.session?.accessToken) {
        onAuthenticated(body.session as SessionData, remember);
      } else if (body.challengeId) {
        setChallengeId(body.challengeId); setStep("mfa");
      } else {
        throw new Error("Phản hồi đăng nhập không hợp lệ.");
      }
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Backend chưa sẵn sàng. Vui lòng thử lại."); }
    finally { setLoading(false); }
  };

  const submitOtp = async (event: React.FormEvent) => {
    event.preventDefault();
    setError("");
    if (!/^\d{6}$/.test(otp)) return setError("Mã xác thực phải gồm đúng 6 chữ số.");
    setLoading(true);
    try {
      const response = await fetch(`${API_URL}/api/v1/auth/mfa`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ challengeId, code: otp, remember }) });
      const body = await response.json().catch(() => ({ detail: response.statusText || "Backend trả phản hồi không hợp lệ." }));
      if (!response.ok) throw new Error(body.detail ?? "Mã xác thực không hợp lệ.");
      onAuthenticated(body as SessionData, remember);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Không thể xác thực."); }
    finally { setLoading(false); }
  };

  return <div className="auth-page">
    <section className="auth-brand-panel" aria-label="Giới thiệu Core Platform">
      <div className="auth-brand"><div className="brand-mark large"><i /><i /><i /><i /></div><div><strong>Core</strong><span>Platform</span></div></div>
      <div className="auth-message"><span className="auth-kicker">Enterprise application foundation</span><h1>Giải pháp tối ưu hóa vận hành doanh nghiệp</h1><p>Quản trị vận hành, tài nguyên, phân quyền từ một trung tâm duy nhất.</p></div>
      <div className="auth-trust"><div><StatusDot /><span>Hệ thống hoạt động ổn định</span></div><small>Core v1.0.0-rc.4 · Secure access</small></div>
    </section>
    <section className="auth-form-panel">
      <div className="auth-card">
        {step === "login" ? <>
          <div className="auth-heading"><span className="mobile-auth-logo">CP</span><h2>Đăng nhập</h2><p>Sử dụng tài khoản nội bộ để truy cập Core Platform.</p></div>
          <form onSubmit={submitLogin} noValidate>
            <label>Email công việc<input autoFocus type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@company.vn" /></label>
            <label>Mật khẩu<div className="password-field"><input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Nhập mật khẩu" /><span>●●●</span></div></label>
            <div className="auth-options"><label className="check-label"><input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} /> Ghi nhớ đăng nhập</label><button type="button" className="auth-link" onClick={() => setError("Vui lòng liên hệ Quản trị viên hệ thống để đặt lại mật khẩu.")}>Quên mật khẩu?</button></div>
            {error && <p className="auth-error" role="alert">{error}</p>}
            <button className="auth-submit" disabled={loading}>{loading ? "Đang xác thực..." : "Tiếp tục"}<span>→</span></button>
          </form>
          <div className="auth-security"><span><AppIcon name="lock" size={15}/></span><p><strong>Kết nối được bảo vệ</strong><small>Phiên đăng nhập được mã hóa và ghi nhận audit.</small></p></div>
        </> : <>
          <button className="auth-back" onClick={() => { setStep("login"); setError(""); }}>← Quay lại</button>
          <div className="auth-heading"><span className="mfa-icon"><AppIcon name="shield" size={22}/></span><h2>Xác thực hai lớp</h2><p>Nhập mã 6 chữ số từ ứng dụng xác thực của bạn.</p></div>
          <form onSubmit={submitOtp} noValidate>
            <label>Mã xác thực<input className="otp-input" autoFocus inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))} placeholder="000000" /></label>
            {error && <p className="auth-error" role="alert">{error}</p>}
            <button className="auth-submit" disabled={loading}>{loading ? "Đang kiểm tra..." : "Xác nhận đăng nhập"}<span>→</span></button>
            <p className="otp-help">Chưa nhận được mã? <button type="button" onClick={() => setError("Mã mới đã được tạo trong ứng dụng xác thực.")}>Gửi lại mã</button></p>
          </form>
        </>}
        <footer>© 2026 Core Platform <span>·</span> Trợ giúp <span>·</span> Chính sách bảo mật</footer>
      </div>
    </section>
  </div>;
}

function PasswordChangeScreen({ onChanged, onSignOut }: { onChanged: () => void; onSignOut: () => void }) {
  const [currentPassword,setCurrentPassword]=useState("");
  const [newPassword,setNewPassword]=useState("");
  const [confirmation,setConfirmation]=useState("");
  const [error,setError]=useState("");
  const [loading,setLoading]=useState(false);
  const submit=async(event:React.FormEvent)=>{event.preventDefault();setError("");
    if(newPassword.length<12||!/[A-Za-z]/.test(newPassword)||!/[0-9]/.test(newPassword))return setError("Mật khẩu mới phải có ít nhất 12 ký tự, gồm cả chữ và số.");
    if(newPassword!==confirmation)return setError("Mật khẩu xác nhận chưa khớp.");
    setLoading(true);try{await apiRequest<void>("/api/v1/auth/change-password",{method:"POST",body:JSON.stringify({currentPassword,newPassword})});onChanged();}
    catch(cause){setError(cause instanceof Error?cause.message:"Không thể đổi mật khẩu.");}finally{setLoading(false);}};
  return <div className="auth-page"><section className="auth-brand-panel" aria-label="Bảo mật tài khoản"><div className="auth-brand"><div className="brand-mark large"><i/><i/><i/><i/></div><div><strong>Core</strong><span>Platform</span></div></div><div className="auth-message"><span className="auth-kicker">Account security</span><h1>Bảo vệ tài khoản quản trị</h1><p>Đổi mật khẩu khởi tạo trước khi truy cập dữ liệu và chức năng quản trị hệ thống.</p></div><div className="auth-trust"><div><StatusDot/><span>Phiên xác thực đã được bảo vệ</span></div><small>Mandatory password change</small></div></section><section className="auth-form-panel"><div className="auth-card"><div className="auth-heading"><span className="mfa-icon"><AppIcon name="lock" size={22}/></span><h2>Đổi mật khẩu bắt buộc</h2><p>Đây là lần đăng nhập đầu tiên hoặc mật khẩu vừa được quản trị viên đặt lại.</p></div><form onSubmit={submit}><label>Mật khẩu hiện tại<div className="password-field"><input autoFocus type="password" autoComplete="current-password" value={currentPassword} onChange={e=>setCurrentPassword(e.target.value)}/><span>●●●</span></div></label><label>Mật khẩu mới<div className="password-field"><input type="password" autoComplete="new-password" value={newPassword} onChange={e=>setNewPassword(e.target.value)}/><span>●●●</span></div></label><label>Xác nhận mật khẩu mới<div className="password-field"><input type="password" autoComplete="new-password" value={confirmation} onChange={e=>setConfirmation(e.target.value)}/><span>●●●</span></div></label>{error&&<p className="auth-error" role="alert">{error}</p>}<button className="auth-submit" disabled={loading}>{loading?"Đang cập nhật...":"Đổi mật khẩu và tiếp tục"}<span>→</span></button><button type="button" className="auth-back password-signout" onClick={onSignOut}>Đăng xuất và dùng tài khoản khác</button></form></div></section></div>;
}

function StatusDot({ tone = "teal" }: { tone?: string }) {
  return <span className={`status-dot ${tone}`} aria-hidden="true" />;
}

function PageTitle({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: React.ReactNode }) {
  return (
    <div className="page-heading">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p className="page-description">{description}</p>
      </div>
      {action}
    </div>
  );
}

function Overview({ onNavigate, data, displayName }: { onNavigate: (view: View) => void; data: BootstrapData; displayName?: string }) {
  const { summary, activities } = data;
  return (
    <>
      <PageTitle
        eyebrow="Core Control Plane"
        title={`Xin chào, ${displayName || "Quản trị viên hệ thống"}`}
        description={`Hệ thống đang hoạt động. Có ${summary.pendingOutbox} sự kiện outbox và ${summary.runningJobs} background job đang xử lý.`}
        action={<button className="primary-button" onClick={() => onNavigate("resources")}><span>＋</span> Tạo resource</button>}
      />

      <section className="health-banner" aria-label="Trạng thái hệ thống">
        <div className="health-mark"><AppIcon name="check-circle" size={18}/></div>
        <div className="health-copy">
          <div className="health-title"><strong>Tất cả dịch vụ cốt lõi đang hoạt động</strong><span className="live-pill"><StatusDot /> Live</span></div>
          <p>Dữ liệu tổng hợp trực tiếp từ backend · Các trạng thái lỗi sẽ được hiển thị tại khu vực vận hành</p>
        </div>
        <button className="text-button" onClick={() => onNavigate("activity")}>Xem chi tiết <span>→</span></button>
      </section>

      <section className="metric-grid" aria-label="Chỉ số chính">
        <article className="metric-card">
          <div className="metric-icon blue"><AppIcon name="database"/></div><span className="metric-label">Resource records</span>
          <strong className="metric-value">{summary.resources.toLocaleString("vi-VN")}</strong><span className="metric-trend positive">Live</span><small>dữ liệu từ PostgreSQL</small>
        </article>
        <article className="metric-card">
          <div className="metric-icon violet"><AppIcon name="modules"/></div><span className="metric-label">Modules hoạt động</span>
          <strong className="metric-value">{data.modules.filter(x => x.status !== "DISABLED").length} <em>/ {summary.modules}</em></strong><span className="metric-trend neutral">Đã đăng ký</span><small>module runtime</small>
        </article>
        <article className="metric-card">
          <div className="metric-icon amber"><AppIcon name="webhook"/></div><span className="metric-label">Outbox đang chờ</span>
          <strong className="metric-value">{summary.pendingOutbox}</strong><span className="metric-trend warning">Pending</span><small>outbox chờ xử lý</small>
        </article>
        <article className="metric-card">
          <div className="metric-icon teal"><AppIcon name="clock"/></div><span className="metric-label">Background jobs</span>
          <strong className="metric-value">{summary.runningJobs}</strong><span className="metric-trend positive">Live</span><small>tác vụ đang xử lý</small>
        </article>
      </section>

      <div className="dashboard-grid">
        <section className="panel activity-panel">
          <div className="panel-header"><div><h2>Hoạt động gần đây</h2><p>Luồng sự kiện và tác vụ trên toàn hệ thống</p></div><button className="ghost-button" onClick={() => onNavigate("activity")}>Xem tất cả</button></div>
          <div className="activity-list">
            {activities.slice(0, 4).map((item) => (
              <div className="activity-row" key={item.id}>
                <div className={`activity-icon ${item.kind.toLowerCase()}`}><AppIcon name={item.kind === "EVENT" ? "webhook" : "clock"} size={15}/></div>
                <div className="activity-main"><strong>{item.name}</strong><span>{item.metadata}</span></div>
                <span className={`state ${item.status.toLowerCase()}`}>{item.status}</span>
                <time>{new Date(item.occurredAt).toLocaleTimeString("vi-VN")}</time>
              </div>
            ))}
          </div>
        </section>

        <aside className="panel quick-panel">
          <div className="panel-header"><div><h2>Truy cập nhanh</h2><p>Các tác vụ thường dùng</p></div></div>
          <button onClick={() => onNavigate("modules")}><span className="quick-icon"><AppIcon name="modules"/></span><div><strong>Quản lý modules</strong><small>Bật, tắt và kiểm tra phiên bản</small></div><b>→</b></button>
          <button onClick={() => onNavigate("access")}><span className="quick-icon"><AppIcon name="shield"/></span><div><strong>Phân quyền</strong><small>Vai trò, policy và phạm vi</small></div><b>→</b></button>
          <button onClick={() => onNavigate("files")}><span className="quick-icon"><AppIcon name="files"/></span><div><strong>Kho tệp</strong><small>{summary.files.toLocaleString("vi-VN")} tệp · {summary.storageGb.toFixed(2)} GB</small></div><b>→</b></button>
        </aside>
      </div>

    </>
  );
}

function Modules({ items, onStatus }: { items: ModuleItem[]; onStatus: (item: ModuleItem) => void }) {
  const [query,setQuery]=useState("");
  const [filter,setFilter]=useState<"ALL"|"ENABLED"|"ATTENTION">("ALL");
  const enabledCount=items.filter(item=>item.status!=="DISABLED").length;
  const attentionCount=items.filter(item=>item.status==="ATTENTION").length;
  const filtered=items.filter(item=>`${item.name} ${item.moduleKey} ${item.description}`.toLowerCase().includes(query.toLowerCase()))
    .filter(item=>filter==="ALL"||(filter==="ENABLED"?item.status!=="DISABLED":item.status==="ATTENTION"));
  return (
    <>
      <PageTitle eyebrow="Runtime composition" title="Modules" description="Theo dõi capability, phiên bản và trạng thái của các module đã đóng gói trong deployment." />
      <div className="filter-row"><div className="search-field"><AppIcon name="search" size={15}/><input value={query} onChange={event=>setQuery(event.target.value)} aria-label="Tìm module" placeholder="Tìm theo tên hoặc capability..." /></div><button className={`filter-chip ${filter==="ALL"?"active":""}`} onClick={()=>setFilter("ALL")}>Tất cả {items.length}</button><button className={`filter-chip ${filter==="ENABLED"?"active":""}`} onClick={()=>setFilter("ENABLED")}>Đang bật {enabledCount}</button><button className={`filter-chip ${filter==="ATTENTION"?"active":""}`} onClick={()=>setFilter("ATTENTION")}>Cần chú ý {attentionCount}</button></div>
      <section className="module-grid">
        {filtered.map((item) => <article className="module-card" key={item.id}>
          <div className="module-top"><div className="module-symbol"><AppIcon name={MODULE_ICONS[item.moduleKey]??"modules"} size={19}/></div><span className={`state ${item.status === "HEALTHY" ? "teal" : item.status === "ATTENTION" ? "amber" : "gray"}`}>{item.status}</span></div>
          <h3>{item.name}</h3><code>{item.moduleKey}</code><p>{item.description}</p>
          <div className="module-meta"><span>v{item.version}</span><span>{item.metric}</span></div>
          <button className="card-action" onClick={() => onStatus(item)}>{item.status === "DISABLED" ? "Bật module" : "Tắt module"} <span>→</span></button>
        </article>)}
      </section>
    </>
  );
}

function DynamicConsole({ onChanged }: { onChanged: () => Promise<void> }) {
  const [definitions,setDefinitions]=useState<DynamicDefinition[]>([]);const [selected,setSelected]=useState("");const [records,setRecords]=useState<Array<{id:string;data:Record<string,unknown>;version:number;updatedAt:string}>>([]);
  const [key,setKey]=useState("");const [name,setName]=useState("");const [classification,setClassification]=useState("INTERNAL");const [schema,setSchema]=useState('{"fields":[{"key":"name","type":"string","required":true}]}');const [recordJson,setRecordJson]=useState('{"name":"Bản ghi mới"}');const [message,setMessage]=useState("");
  const authHeaders=()=>({Authorization:`Bearer ${window.localStorage.getItem("core-access-token")||window.sessionStorage.getItem("core-access-token")||""}`});
  const loadDefinitions=async()=>{const r=await fetch(`${API_URL}/api/v1/dynamic/definitions`,{headers:authHeaders()});if(!r.ok)throw new Error("Không thể tải definitions");const body=await r.json();setDefinitions(body);if(!selected&&body.length)setSelected(body[0].resourceKey);};
  const loadRecords=async(resourceKey:string)=>{if(!resourceKey){setRecords([]);return;}const r=await fetch(`${API_URL}/api/v1/dynamic/${resourceKey}/records?page=0&size=50`,{headers:authHeaders()});if(!r.ok)throw new Error("Không thể tải records");setRecords((await r.json()).items);};
  useEffect(()=>{loadDefinitions().catch(e=>setMessage(e.message));},[]);
  useEffect(()=>{loadRecords(selected).catch(e=>setMessage(e.message));},[selected]);
  const createDefinition=async()=>{try{const r=await fetch(`${API_URL}/api/v1/dynamic/definitions`,{method:"POST",headers:{...authHeaders(),"Content-Type":"application/json"},body:JSON.stringify({resourceKey:key,name,schema:JSON.parse(schema),classification})});if(!r.ok)throw new Error((await r.json()).detail||"Tạo definition thất bại");setKey("");setName("");setMessage("Đã tạo definition");await loadDefinitions();await onChanged();}catch(e){setMessage(e instanceof Error?e.message:"Dữ liệu không hợp lệ");}};
  const createRecord=async()=>{try{const r=await fetch(`${API_URL}/api/v1/dynamic/${selected}/records`,{method:"POST",headers:{...authHeaders(),"Content-Type":"application/json"},body:JSON.stringify(JSON.parse(recordJson))});if(!r.ok)throw new Error((await r.json()).detail||"Tạo record thất bại");setMessage("Đã tạo record và revision");await loadRecords(selected);await onChanged();}catch(e){setMessage(e instanceof Error?e.message:"JSON không hợp lệ");}};
  const exportCsv=async()=>{const r=await fetch(`${API_URL}/api/v1/dynamic/${selected}/export.csv`,{headers:authHeaders()});if(!r.ok)return setMessage("Export thất bại");const url=URL.createObjectURL(await r.blob());const a=document.createElement("a");a.href=url;a.download=`${selected}.csv`;a.click();URL.revokeObjectURL(url);};
  const importCsv=async(file:File)=>{const form=new FormData();form.append("file",file);const r=await fetch(`${API_URL}/api/v1/dynamic/${selected}/import.csv`,{method:"POST",headers:authHeaders(),body:form});const body=await r.json();if(!r.ok)return setMessage(body.detail||"Import thất bại");setMessage(`Import thành công ${body.imported}, lỗi ${body.failed}`);await loadRecords(selected);await onChanged();};
  return <section className="panel settings-form"><div className="panel-header"><div><h2>Dynamic Resource Console</h2><p>Definition, schema validation, Generic CRUD, history và CSV.</p></div><span className="live-pill"><StatusDot /> API thật</span></div>
    {message&&<p className="auth-error" role="status">{message}</p>}
    <div className="form-grid"><label>Resource key<input value={key} onChange={e=>setKey(e.target.value)} placeholder="customer-profile" /></label><label>Tên definition<input value={name} onChange={e=>setName(e.target.value)} placeholder="Customer Profile" /></label><label>Phân loại dữ liệu<select value={classification} onChange={e=>setClassification(e.target.value)}><option value="INTERNAL">Internal</option><option value="CONFIDENTIAL">Confidential</option><option value="RESTRICTED">Restricted</option><option value="PUBLIC">Public</option><option value="">Chưa phân loại (cần duyệt)</option></select></label></div>
    <label>JSON Schema<textarea rows={4} value={schema} onChange={e=>setSchema(e.target.value)} /></label><button className="secondary-button" onClick={createDefinition}>Tạo definition</button>
    <hr/><label>Definition<select value={selected} onChange={e=>setSelected(e.target.value)}><option value="">Chọn definition</option>{definitions.map(d=><option key={d.id} value={d.resourceKey}>{d.name} · v{d.version}</option>)}</select></label>
    {selected&&<><label>Record JSON<textarea rows={4} value={recordJson} onChange={e=>setRecordJson(e.target.value)} /></label><div className="filter-row"><button className="primary-button" onClick={createRecord}>Tạo record</button><button className="secondary-button" onClick={exportCsv}>Export CSV</button><label className="secondary-button">Import CSV<input hidden type="file" accept=".csv,text/csv" onChange={e=>e.target.files?.[0]&&importCsv(e.target.files[0])}/></label></div>
    <div className="data-table"><div className="table-row table-head"><span>ID</span><span>Data</span><span>Version</span><span>Cập nhật</span><span></span><span></span></div>{records.map(r=><div className="table-row" key={r.id}><span><code>{r.id.slice(0,8)}</code></span><span><code>{JSON.stringify(r.data)}</code></span><span>v{r.version}</span><span>{new Date(r.updatedAt).toLocaleString("vi-VN")}</span><span></span><span></span></div>)}</div></>}
  </section>;
}

function Resources({ items, onChanged }: { items: ResourceItem[]; onChanged: () => Promise<void> }) {
  const [query, setQuery] = useState("");
  const [mode,setMode]=useState<"ALL"|"DOMAIN"|"DYNAMIC">("ALL");
  const filtered = items.filter((r) => `${r.name} ${r.ownerModule}`.toLowerCase().includes(query.toLowerCase())).filter(item=>mode==="ALL"||item.storageMode===mode);
  return (
    <>
      <PageTitle eyebrow="Three-Plane Registry" title="Resources" description="Domain aggregate và Dynamic Resource cùng đăng ký capability nhưng giữ persistence độc lập." />
      <section className="panel table-panel">
        <div className="table-tools"><div className="search-field wide"><AppIcon name="search" size={15}/><input value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Tìm resource" placeholder="Tìm resource, module..." /></div><button className={`filter-chip ${mode==="ALL"?"active":""}`} onClick={()=>setMode("ALL")}>Tất cả</button><button className={`filter-chip ${mode==="DOMAIN"?"active":""}`} onClick={()=>setMode("DOMAIN")}>Domain</button><button className={`filter-chip ${mode==="DYNAMIC"?"active":""}`} onClick={()=>setMode("DYNAMIC")}>Dynamic</button></div>
        <div className="data-table" role="table" aria-label="Danh sách resources">
          <div className="table-row table-head" role="row"><span>Tên resource</span><span>Storage mode</span><span>Owner module</span><span>Records</span><span>Schema</span><span>Cập nhật</span></div>
          {filtered.map((item) => <div className="table-row" role="row" key={item.name}>
            <span><b className="resource-glyph"><AppIcon name="database" size={14}/></b><strong>{item.name}</strong></span><span><em className={`type-pill ${item.storageMode.toLowerCase()}`}>{item.storageMode}</em></span><span><code>{item.ownerModule}</code></span><span>{item.records.toLocaleString("vi-VN")}</span><span>{item.schemaVersion}</span><span>{new Date(item.updatedAt).toLocaleString("vi-VN")}</span>
          </div>)}
        </div>
      </section>
      <DynamicConsole onChanged={onChanged} />
    </>
  );
}

function Users() {
  const [items,setItems]=useState<AccessUser[]>([]);const [roles,setRoles]=useState<AccessRole[]>([]);const [organizations,setOrganizations]=useState<Organization[]>([]);
  const [email,setEmail]=useState("");const [displayName,setDisplayName]=useState("");const [password,setPassword]=useState("");const [roleId,setRoleId]=useState("");const [orgId,setOrgId]=useState("");const [message,setMessage]=useState("");
  const load=async()=>{try{const [usersData,rolesData,orgData]=await Promise.all([apiRequest<AccessUser[]>("/api/v1/access/users"),apiRequest<AccessRole[]>("/api/v1/access/roles"),apiRequest<Organization[]>("/api/v1/access/organizations")]);setItems(usersData);setRoles(rolesData);setOrganizations(orgData);if(!roleId&&rolesData[0])setRoleId(rolesData[0].id);setMessage("");}catch(error){setMessage(error instanceof Error?error.message:"Không thể tải người dùng");}};
  useEffect(()=>{void load();},[]);
  const create=async()=>{try{if(!roleId)throw new Error("Vui lòng chọn vai trò.");await apiRequest<AccessUser>("/api/v1/access/users",{method:"POST",body:JSON.stringify({email,displayName,password,roleIds:[roleId],orgId:orgId||null})});setEmail("");setDisplayName("");setPassword("");setMessage("Đã tạo tài khoản và gán vai trò.");await load();}catch(error){setMessage(error instanceof Error?error.message:"Không thể tạo tài khoản");}};
  const toggle=async(item:AccessUser)=>{try{await apiRequest<void>(`/api/v1/access/users/${item.id}/enabled`,{method:"PATCH",body:JSON.stringify({enabled:!item.enabled})});await load();}catch(error){setMessage(error instanceof Error?error.message:"Không thể đổi trạng thái");}};
  const reset=async(item:AccessUser)=>{try{const result=await apiRequest<{tempPassword:string}>(`/api/v1/access/users/${item.id}/reset-password`,{method:"POST"});setMessage(`Mật khẩu tạm thời của ${item.email}: ${result.tempPassword} — chỉ sao chép và bàn giao một lần.`);}catch(error){setMessage(error instanceof Error?error.message:"Không thể đặt lại mật khẩu");}};
  return <><PageTitle eyebrow="Organization & access" title="Người dùng" description="Quản lý tài khoản nội bộ, trạng thái và vai trò trong deployment hiện tại." />
    {message&&<p className="operation-message" role="status">{message}</p>}
    <section className="panel settings-form"><div className="panel-header"><div><h2>Tạo tài khoản</h2><p>Mật khẩu ban đầu tối thiểu 12 ký tự; người dùng phải đổi sau khi được đặt lại.</p></div></div><div className="form-grid"><label>Họ và tên<input value={displayName} onChange={e=>setDisplayName(e.target.value)} /></label><label>Email<input type="email" value={email} onChange={e=>setEmail(e.target.value)} /></label><label>Mật khẩu ban đầu<input type="password" value={password} onChange={e=>setPassword(e.target.value)} /></label><label>Vai trò<select value={roleId} onChange={e=>setRoleId(e.target.value)}>{roles.map(role=><option key={role.id} value={role.id}>{role.name}</option>)}</select></label><label>Đơn vị<select value={orgId} onChange={e=>setOrgId(e.target.value)}><option value="">Không gán</option>{organizations.map(org=><option key={org.id} value={org.id}>{org.name}</option>)}</select></label></div><button className="primary-button" onClick={create}>＋ Tạo người dùng</button></section>
    <section className="panel table-panel"><div className="data-table access-table"><div className="table-row table-head"><span>Người dùng</span><span>Vai trò</span><span>Trạng thái</span><span>Ngày tạo</span><span>Thao tác</span><span></span></div>{items.map(item=><div className="table-row" key={item.id}><span><strong>{item.displayName}</strong><small>{item.email}</small></span><span>{item.roles.join(", ")||"Chưa gán"}</span><span><em className={`state ${item.enabled?"teal":"gray"}`}>{item.enabled?"ACTIVE":"DISABLED"}</em></span><span>{new Date(item.createdAt).toLocaleDateString("vi-VN")}</span><span><button className="text-button" onClick={()=>void toggle(item)}>{item.enabled?"Vô hiệu hóa":"Kích hoạt"}</button></span><span><button className="text-button" onClick={()=>void reset(item)}>Đặt lại mật khẩu</button></span></div>)}</div></section>
  </>;
}

function Organizations() {
  const [items,setItems]=useState<Organization[]>([]);const [code,setCode]=useState("");const [name,setName]=useState("");const [parentCode,setParentCode]=useState("");const [message,setMessage]=useState("");
  const load=async()=>{try{setItems(await apiRequest<Organization[]>("/api/v1/access/organizations"));}catch(error){setMessage(error instanceof Error?error.message:"Không thể tải cơ cấu tổ chức");}};
  useEffect(()=>{void load();},[]);
  const create=async()=>{try{await apiRequest<Organization>("/api/v1/access/organizations",{method:"POST",body:JSON.stringify({code,name,parentCode:parentCode||null})});setCode("");setName("");setParentCode("");setMessage("Đã tạo đơn vị tổ chức.");await load();}catch(error){setMessage(error instanceof Error?error.message:"Không thể tạo đơn vị");}};
  return <><PageTitle eyebrow="Organization model" title="Cơ cấu tổ chức" description="Công ty, đơn vị và phòng ban trong deployment của khách hàng; không phải mô hình SaaS nhiều khách hàng." />{message&&<p className="operation-message" role="status">{message}</p>}
    <section className="panel settings-form"><div className="form-grid"><label>Mã đơn vị<input value={code} onChange={e=>setCode(e.target.value)} placeholder="sales-hcm" /></label><label>Tên đơn vị<input value={name} onChange={e=>setName(e.target.value)} placeholder="Phòng Kinh doanh HCM" /></label><label>Đơn vị cha<select value={parentCode} onChange={e=>setParentCode(e.target.value)}><option value="">Cấp cao nhất</option>{items.map(item=><option key={item.id} value={item.code}>{item.name}</option>)}</select></label></div><button className="primary-button" onClick={create}>＋ Tạo đơn vị</button></section>
    <section className="panel table-panel"><div className="data-table"><div className="table-row table-head"><span>Mã</span><span>Tên đơn vị</span><span>Đơn vị cha</span><span>Trạng thái</span><span>Ngày tạo</span><span></span></div>{items.map(item=><div className="table-row" key={item.id}><span><code>{item.code}</code></span><span><strong>{item.name}</strong></span><span>{item.parentCode||"—"}</span><span><em className="state teal">{item.status}</em></span><span>{new Date(item.createdAt).toLocaleDateString("vi-VN")}</span><span></span></div>)}</div></section>
  </>;
}

function Access() {
  const [roles,setRoles]=useState<AccessRole[]>([]);const [policies,setPolicies]=useState<AccessPolicy[]>([]);const [code,setCode]=useState("");const [name,setName]=useState("");const [message,setMessage]=useState("");
  const [policyCode,setPolicyCode]=useState("");const [resourceType,setResourceType]=useState("");const [action,setAction]=useState("READ");const [effect,setEffect]=useState("ALLOW");
  const load=async()=>{try{const [roleData,policyData]=await Promise.all([apiRequest<AccessRole[]>("/api/v1/access/roles"),apiRequest<AccessPolicy[]>("/api/v1/access/policies")]);setRoles(roleData);setPolicies(policyData);}catch(error){setMessage(error instanceof Error?error.message:"Không thể tải phân quyền");}};
  useEffect(()=>{void load();},[]);
  const createRole=async()=>{try{await apiRequest<AccessRole>("/api/v1/access/roles",{method:"POST",body:JSON.stringify({code,name})});setCode("");setName("");setMessage("Đã tạo vai trò.");await load();}catch(error){setMessage(error instanceof Error?error.message:"Không thể tạo vai trò");}};
  const createPolicy=async()=>{try{await apiRequest<AccessPolicy>("/api/v1/access/policies",{method:"POST",body:JSON.stringify({code:policyCode,resourceType,action,effect,condition:"{}"})});setPolicyCode("");setResourceType("");setMessage("Đã tạo policy.");await load();}catch(error){setMessage(error instanceof Error?error.message:"Không thể tạo policy");}};
  return <><PageTitle eyebrow="Identity & authorization" title="Vai trò & phân quyền" description="Quản lý vai trò và policy. Backend luôn kiểm tra quyền theo nguyên tắc fail-closed." />{message&&<p className="operation-message" role="status">{message}</p>}
    <section className="access-summary"><div><span>Vai trò</span><strong>{roles.length}</strong><small>{roles.filter(role=>role.systemRole).length} vai trò hệ thống</small></div><div><span>Policies</span><strong>{policies.length}</strong><small>{policies.filter(policy=>policy.enabled).length} đang hiệu lực</small></div><div><span>Allow</span><strong>{policies.filter(policy=>policy.effect==="ALLOW").length}</strong><small>quy tắc cấp quyền</small></div><div><span>Deny</span><strong>{policies.filter(policy=>policy.effect==="DENY").length}</strong><small>được ưu tiên khi đánh giá</small></div></section>
    <section className="panel settings-form"><div className="form-grid"><label>Mã vai trò<input value={code} onChange={e=>setCode(e.target.value)} placeholder="department-manager" /></label><label>Tên vai trò<input value={name} onChange={e=>setName(e.target.value)} placeholder="Trưởng phòng" /></label></div><button className="primary-button" onClick={createRole}>＋ Tạo vai trò</button></section>
    <section className="panel settings-form"><div className="panel-header"><div><h2>Tạo policy</h2><p>Policy được kiểm tra tại API theo nguyên tắc fail-closed.</p></div></div><div className="form-grid"><label>Mã policy<input value={policyCode} onChange={e=>setPolicyCode(e.target.value)} placeholder="customer-read" /></label><label>Resource type<input value={resourceType} onChange={e=>setResourceType(e.target.value)} placeholder="CUSTOMER" /></label><label>Action<select value={action} onChange={e=>setAction(e.target.value)}><option>READ</option><option>CREATE</option><option>UPDATE</option><option>DELETE</option><option>MANAGE</option></select></label><label>Effect<select value={effect} onChange={e=>setEffect(e.target.value)}><option>ALLOW</option><option>DENY</option></select></label></div><button className="primary-button" onClick={createPolicy}><AppIcon name="plus" size={14}/> Tạo policy</button></section>
    <div className="role-grid">{roles.map((role,index)=><article className="panel role-card" key={role.id}><div className={`role-badge ${["violet","blue","amber","teal"][index%4]}`}>{role.name.split(" ").map(value=>value[0]).join("").slice(0,2)}</div><div className="role-copy"><h3>{role.name}</h3><p><code>{role.code}</code></p></div><span className={`state ${role.systemRole?"amber":"teal"}`}>{role.systemRole?"SYSTEM":"CUSTOM"}</span></article>)}</div>
    <section className="panel table-panel"><div className="data-table"><div className="table-row table-head"><span>Policy</span><span>Resource</span><span>Action</span><span>Effect</span><span>Version</span><span>Trạng thái</span></div>{policies.map(policy=><div className="table-row" key={policy.id}><span><code>{policy.code}</code></span><span>{policy.resourceType}</span><span>{policy.action}</span><span><em className={`state ${policy.effect==="ALLOW"?"teal":"amber"}`}>{policy.effect}</em></span><span>v{policy.version}</span><span>{policy.enabled?"Đang hiệu lực":"Đã tắt"}</span></div>)}</div></section>
    <section className="panel policy-banner"><div className="policy-icon"><AppIcon name="shield"/></div><div><h3>Permission engine đang ở chế độ fail-closed</h3><p>Menu chỉ hỗ trợ khám phá chức năng; API vẫn là điểm thực thi quyền bắt buộc.</p></div></section>
  </>;
}

function Activity({ items }: { items: ActivityItem[] }) {
  const [jobs,setJobs]=useState<JobItem[]>([]);const [outbox,setOutbox]=useState<OutboxItem[]>([]);const [message,setMessage]=useState("");const [loading,setLoading]=useState(false);
  const load=async()=>{setLoading(true);try{const [jobItems,outboxItems]=await Promise.all([apiRequest<JobItem[]>("/api/v1/control-plane/jobs"),apiRequest<OutboxItem[]>("/api/v1/control-plane/outbox")]);setJobs(jobItems);setOutbox(outboxItems);setMessage("");}catch(error){setMessage(error instanceof Error?error.message:"Không thể tải dữ liệu vận hành");}finally{setLoading(false);}};
  useEffect(()=>{void load();},[]);
  const jobAction=async(item:JobItem,operation:"retry"|"cancel")=>{try{await apiRequest<JobItem>(`/api/v1/control-plane/jobs/${item.id}/${operation}`,{method:"POST"});await load();}catch(error){setMessage(error instanceof Error?error.message:"Không thể cập nhật job");}};
  const replay=async(item:OutboxItem)=>{try{await apiRequest(`/api/v1/control-plane/outbox/${item.id}/replay`,{method:"POST"});await load();}catch(error){setMessage(error instanceof Error?error.message:"Không thể replay event");}};
  const pendingOutbox=outbox.filter(item=>item.status==="PENDING").length;
  const runningJobs=jobs.filter(item=>item.status==="RUNNING").length;
  const retrying=jobs.filter(item=>item.status==="RETRYING").length+outbox.filter(item=>item.status==="RETRYING").length;
  const dead=jobs.filter(item=>item.status==="DEAD").length+outbox.filter(item=>item.status==="DEAD").length;
  return (
    <>
      <PageTitle eyebrow="Durable processing" title="Events & Jobs" description="Theo dõi outbox, background jobs, retry và dead-letter flow bằng dữ liệu runtime." action={<button className="secondary-button" disabled={loading} onClick={()=>void load()}><AppIcon name="refresh" size={14}/> {loading?"Đang tải":"Làm mới"}</button>} />
      {message&&<p className="operation-message" role="status">{message}</p>}
      <section className="queue-grid"><div className="queue-card"><span>Outbox pending</span><strong>{pendingOutbox}</strong><small>{outbox.length} event gần nhất</small></div><div className="queue-card"><span>Jobs running</span><strong>{runningJobs}</strong><small>{jobs.length} job gần nhất</small></div><div className="queue-card"><span>Đang retry</span><strong>{retrying}</strong><small>Job và outbox</small></div><div className="queue-card"><span>Dead-letter</span><strong>{dead}</strong><small>Cần quản trị viên xử lý</small></div></section>
      <div className="operations-grid">
        <section className="panel operation-card"><div className="panel-header"><div><h2>Background jobs</h2><p>Retry và hủy theo trạng thái hợp lệ</p></div><span className="live-pill"><StatusDot/> API thật</span></div><div className="operation-list">{jobs.slice(0,20).map(item=><article key={item.id}><span className="operation-icon"><AppIcon name="clock" size={15}/></span><div><strong>{item.jobType}</strong><small>{item.tenantKey} · {item.attempts} lần chạy{item.lastError?` · ${item.lastError}`:""}</small></div><em className={`state ${item.status.toLowerCase()}`}>{item.status}</em><div className="operation-actions">{["DEAD","RETRYING","CANCELLED"].includes(item.status)&&<button className="text-button" onClick={()=>void jobAction(item,"retry")}>Chạy lại</button>}{["PENDING","RETRYING"].includes(item.status)&&<button className="text-button danger-text" onClick={()=>void jobAction(item,"cancel")}>Hủy</button>}</div></article>)}{jobs.length===0&&<p className="empty-state">Chưa có background job.</p>}</div></section>
        <section className="panel operation-card"><div className="panel-header"><div><h2>Transactional outbox</h2><p>At-least-once delivery · idempotent consumer</p></div><span className="live-pill"><StatusDot/> API thật</span></div><div className="operation-list">{outbox.slice(0,20).map(item=><article key={item.id}><span className="operation-icon"><AppIcon name="webhook" size={15}/></span><div><strong>{item.eventType}</strong><small>{item.tenantKey} · {item.attempts} lần gửi{item.lastError?` · ${item.lastError}`:""}</small></div><em className={`state ${item.status.toLowerCase()}`}>{item.status}</em><div className="operation-actions">{["DEAD","DELIVERED"].includes(item.status)&&<button className="text-button" onClick={()=>void replay(item)}>Replay</button>}</div></article>)}{outbox.length===0&&<p className="empty-state">Chưa có outbox event.</p>}</div></section>
      </div>
      <section className="panel timeline-panel"><div className="panel-header"><div><h2>Hoạt động gần đây</h2><p>Dữ liệu tổng hợp từ runtime platform</p></div><span className="live-pill"><StatusDot /> Dữ liệu thật</span></div>{items.map((item) => <div className="activity-row expanded" key={item.id}><div className={`activity-icon ${item.kind.toLowerCase()}`}><AppIcon name={item.kind === "EVENT" ? "webhook" : "clock"} size={15}/></div><div className="activity-main"><strong>{item.name}</strong><span>{item.metadata}</span></div><span className={`state ${item.status.toLowerCase()}`}>{item.status}</span><time>{new Date(item.occurredAt).toLocaleString("vi-VN")}</time></div>)}</section>
    </>
  );
}

function Files({ items, storageGb, onUpload, onDownload }: { items: FileItem[]; storageGb: number; onUpload:(file:File)=>Promise<void>; onDownload:(item:FileItem)=>Promise<void> }) {
  const [query,setQuery]=useState("");const [attentionOnly,setAttentionOnly]=useState(false);
  const filtered=items.filter(item=>`${item.name} ${item.mediaType}`.toLowerCase().includes(query.toLowerCase())).filter(item=>!attentionOnly||item.status==="QUARANTINE");
  return (
    <>
      <PageTitle eyebrow="Object storage" title="Tệp tin" description="Quản lý metadata, checksum, phân loại và nội dung file theo tenant." action={<label className="primary-button"><AppIcon name="upload" size={14}/> Tải tệp lên<input hidden type="file" onChange={e=>e.target.files?.[0]&&onUpload(e.target.files[0])}/></label>} />
      <section className="storage-card"><div><span className="storage-icon"><AppIcon name="files"/></span><div><strong>{storageGb.toFixed(2)} GB</strong><p>Dung lượng metadata file đã ghi nhận</p></div></div><div className="storage-progress"><i /></div><div className="storage-stats"><span>{items.length.toLocaleString("vi-VN")} tệp</span><span>{items.filter(x=>x.status === "QUARANTINE").length} quarantine</span><span>Dữ liệu từ PostgreSQL</span></div></section>
      <section className="panel table-panel"><div className="table-tools"><div className="search-field wide"><AppIcon name="search" size={15}/><input value={query} onChange={event=>setQuery(event.target.value)} aria-label="Tìm tệp" placeholder="Tìm tên tệp, media type..." /></div><button className={`filter-chip ${!attentionOnly?"active":""}`} onClick={()=>setAttentionOnly(false)}>Tất cả</button><button className={`filter-chip ${attentionOnly?"active":""}`} onClick={()=>setAttentionOnly(true)}>Cần xử lý</button></div><div className="file-table"><div className="file-row file-head"><span>Tệp</span><span>Kích thước</span><span>Phân loại</span><span>Trạng thái</span><span>Cập nhật</span></div>{filtered.map((f) => <button className="file-row" key={f.id} onClick={()=>onDownload(f)}><span><b><AppIcon name="file" size={15}/></b><span><strong>{f.name}</strong><small>{f.mediaType}</small></span></span><span>{(f.sizeBytes/1024/1024).toFixed(2)} MB</span><span>{f.classification}</span><span><em className={`state ${f.status.toLowerCase()}`}>{f.status}</em></span><span>{new Date(f.updatedAt).toLocaleString("vi-VN")} <AppIcon name="download" size={14}/></span></button>)}</div></section>
    </>
  );
}

function Settings({ values, onSave }: { values: Record<string,string>; onSave: (items: Array<{key:string;value:string}>) => Promise<void> }) {
  const [name,setName]=useState(values["environment.name"] ?? "core-production-vn");
  const [tier,setTier]=useState(values["environment.tier"] ?? "standard");
  const [region,setRegion]=useState(values["environment.region"] ?? "Ho Chi Minh City");
  const [url,setUrl]=useState(values["environment.publicUrl"] ?? "https://corejava.sgodata.com");
  return (
    <>
      <PageTitle eyebrow="Deployment configuration" title="Cấu hình" description="Thông tin môi trường và các chính sách vận hành đang có hiệu lực." action={<button className="primary-button" onClick={() => onSave([{key:"environment.name",value:name},{key:"environment.tier",value:tier},{key:"environment.region",value:region},{key:"environment.publicUrl",value:url}])}>Lưu thay đổi</button>} />
      <div className="settings-layout single"><section className="panel settings-form"><h2>Thông tin deployment</h2><p>Thay đổi được lưu vào PostgreSQL và ghi audit. Tenant kỹ thuật không được trình bày như một chức năng SaaS.</p><label>Tên môi trường<input value={name} onChange={e=>setName(e.target.value)} /></label><div className="form-grid"><label>Service tier<select value={tier} onChange={e=>setTier(e.target.value)}><option value="pilot">Pilot</option><option value="standard">Standard</option><option value="critical">Critical</option></select></label><label>Khu vực<input value={region} onChange={e=>setRegion(e.target.value)} /></label></div><label>Public base URL<input value={url} onChange={e=>setUrl(e.target.value)} /></label></section></div>
    </>
  );
}

function BusinessHome({ section, onOpen }: { section: NavigationSection; onOpen: (item: NavigationItem) => void }) {
  const pages = section.items.filter(item => item.type === "PAGE" && item.viewKey !== "home");
  return <>
    <PageTitle eyebrow="Ứng dụng doanh nghiệp" title="Trang chủ" description="Các phân hệ được đăng ký động từ module đang bật và chỉ hiển thị theo quyền của tài khoản." />
    <section className="business-hero panel"><div><span className="business-hero-icon"><AppIcon name="leaf" size={24}/></span><p className="eyebrow">Điều hướng hợp nhất</p><h2>Chọn phân hệ để bắt đầu</h2><p>Module mới tự đăng ký vào đúng nhóm menu. Giao diện không tách Workspace và không giả định mô hình SaaS.</p></div><strong>{pages.length}<small>phân hệ được cấp quyền</small></strong></section>
    <section className="business-module-grid" aria-label="Phân hệ nghiệp vụ">
      {pages.map(item => <button key={item.key} className="business-module-card" onClick={() => onOpen(item)}><span><AppIcon name={item.icon}/></span><div><small>{item.ownerModule}</small><strong>{item.label}</strong><p>{item.keywords.slice(0,3).join(" · ")}</p></div><b>→</b></button>)}
      {pages.length === 0 && <div className="empty-workspace"><span><AppIcon name="apps" size={24}/></span><h2>Chưa có phân hệ được cấp quyền</h2><p>Liên hệ quản trị viên để bật module hoặc gán policy phù hợp.</p></div>}
    </section>
  </>;
}

export default function Home() {
  const [authenticated,setAuthenticated]=useState(false);const [authReady,setAuthReady]=useState(false);const [user,setUser]=useState<UserInfo|null>(null);
  const [navigation,setNavigation]=useState<NavigationModel|null>(null);const [expandedSection,setExpandedSection]=useState("");const [expandedGroup,setExpandedGroup]=useState("");const [view,setView]=useState<View>("home");
  const [sidebarOpen,setSidebarOpen]=useState(false);const [commandOpen,setCommandOpen]=useState(false);const [commandQuery,setCommandQuery]=useState("");const [notificationsOpen,setNotificationsOpen]=useState(false);const [profileOpen,setProfileOpen]=useState(false);const [logoutOpen,setLogoutOpen]=useState(false);
  const [apiOnline,setApiOnline]=useState(false);const [data,setData]=useState<BootstrapData|null>(null);const [operationError,setOperationError]=useState("");const [passwordChangeRequired,setPasswordChangeRequired]=useState(false);
  const authHeaders=()=>({Authorization:`Bearer ${storedToken()}`});

  useEffect(()=>{const existing=storedToken();if(!existing){setAuthReady(true);return;}void apiRequest<UserInfo>("/api/v1/auth/me").then(account=>{setUser(account);setAuthenticated(true);setApiOnline(true);}).catch(()=>clearSession()).finally(()=>setAuthReady(true));},[]);

  const selectRoute=(model:NavigationModel)=>{const pages=model.sections.flatMap(section=>section.items.filter(item=>item.type==="PAGE").map(item=>({section,item})));const route=window.location.pathname;const selected=pages.find(entry=>entry.item.route===route)||pages.find(entry=>entry.item.key==="core.home")||pages[0];if(selected){setView(selected.item.viewKey as View);setExpandedSection(selected.section.key);setExpandedGroup(selected.item.parentKey);if(route!==selected.item.route)window.history.replaceState(null,"",selected.item.route);}};
  const loadNavigation=async()=>{const model=await apiRequest<NavigationModel>("/api/v1/navigation/me");setNavigation(model);selectRoute(model);return model;};
  const refresh=async()=>setData(await apiRequest<BootstrapData>("/api/v1/control-plane/bootstrap"));

  useEffect(()=>{if(!authenticated||passwordChangeRequired)return;void(async()=>{try{const [me,model]=await Promise.all([apiRequest<UserInfo>("/api/v1/auth/me"),loadNavigation()]);setUser(me);if(model.sections.some(section=>section.key==="system-administration"))await refresh();setApiOnline(true);setOperationError("");}catch(error){if(error instanceof ApiProblemError&&error.code==="PASSWORD_CHANGE_REQUIRED"){setPasswordChangeRequired(true);setNavigation(null);return;}setApiOnline(false);setOperationError("Không thể khởi tạo ứng dụng. Vui lòng đăng nhập lại hoặc kiểm tra backend.");}})();},[authenticated,passwordChangeRequired]);
  useEffect(()=>{const handler=(event:KeyboardEvent)=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==="k"){event.preventDefault();setCommandOpen(true);}if(event.key==="Escape"){setCommandOpen(false);setNotificationsOpen(false);setProfileOpen(false);setLogoutOpen(false);setSidebarOpen(false);}};window.addEventListener("keydown",handler);return()=>window.removeEventListener("keydown",handler);},[]);
  useEffect(()=>{if(!navigation)return;const handler=()=>selectRoute(navigation);window.addEventListener("popstate",handler);return()=>window.removeEventListener("popstate",handler);},[navigation]);

  const sections=navigation?.sections??[];const allPages=useMemo(()=>sections.flatMap(section=>section.items.filter(item=>item.type==="PAGE").map(item=>({section,item}))),[sections]);
  const currentEntry=allPages.find(entry=>entry.item.viewKey===view);const currentLabel=currentEntry?.item.label??"Trang chủ";const currentSection=currentEntry?.section;const currentSectionLabel=currentSection?.key==="home"?"Ứng dụng":currentSection?.label||"Ứng dụng";
  const favoriteEntries=(navigation?.favoriteKeys??[]).map(key=>allPages.find(entry=>entry.item.key===key)).filter((entry):entry is {section:NavigationSection;item:NavigationItem}=>Boolean(entry));
  const commandEntries=allPages.filter(entry=>`${entry.item.label} ${entry.item.keywords.join(" ")} ${entry.section.label}`.toLowerCase().includes(commandQuery.toLowerCase())).sort((left,right)=>{const recent=navigation?.recentKeys??[];const leftIndex=recent.indexOf(left.item.key),rightIndex=recent.indexOf(right.item.key);return(leftIndex<0?999:leftIndex)-(rightIndex<0?999:rightIndex)||left.item.sortOrder-right.item.sortOrder;});

  const savePreferences=async(favoriteKeys:string[],recentKeys:string[])=>{try{setNavigation(await apiRequest<NavigationModel>("/api/v1/navigation/me/preferences",{method:"PUT",body:JSON.stringify({favoriteKeys,recentKeys})}));}catch{setOperationError("Không thể lưu tùy chọn điều hướng.");}};
  const openItem=(item:NavigationItem)=>{if(item.type!=="PAGE")return;const entry=allPages.find(candidate=>candidate.item.key===item.key);setView(item.viewKey as View);if(entry)setExpandedSection(entry.section.key);setExpandedGroup(item.parentKey);setSidebarOpen(false);setCommandOpen(false);setCommandQuery("");if(window.location.pathname!==item.route)window.history.pushState(null,"",item.route);window.scrollTo({top:0,behavior:"smooth"});if(navigation){const recent=[item.key,...navigation.recentKeys.filter(key=>key!==item.key)].slice(0,10);void savePreferences(navigation.favoriteKeys,recent);}};
  const navigate=(next:View)=>{const entry=allPages.find(candidate=>candidate.item.viewKey===next);if(entry)openItem(entry.item);};
  const toggleFavorite=(item:NavigationItem)=>{if(!navigation)return;const favoriteKeys=navigation.favoriteKeys.includes(item.key)?navigation.favoriteKeys.filter(key=>key!==item.key):[...navigation.favoriteKeys,item.key].slice(0,20);void savePreferences(favoriteKeys,navigation.recentKeys);};
  const mutate=async(path:string,method:string,body?:unknown)=>{setOperationError("");try{await apiRequest<void>(path,{method,body:body===undefined?undefined:JSON.stringify(body)});await refresh();}catch(error){const message=error instanceof Error?error.message:"Thao tác thất bại";setOperationError(message);throw error;}};
  const changeModuleStatus=async(item:ModuleItem)=>{try{await mutate(`/api/v1/control-plane/modules/${item.id}/status`,"PATCH",{status:item.status==="DISABLED"?"HEALTHY":"DISABLED"});await loadNavigation();}catch{}};
  const uploadFile=async(file:File)=>{setOperationError("");const form=new FormData();form.append("file",file);const response=await fetch(`${API_URL}/api/v1/files?classification=INTERNAL`,{method:"POST",headers:authHeaders(),body:form});if(!response.ok){const problem=await response.json().catch(()=>({}));setOperationError(problem.detail||"Upload thất bại");return;}await refresh();};
  const downloadFile=async(item:FileItem)=>{const response=await fetch(`${API_URL}/api/v1/files/${item.id}/content`,{headers:authHeaders()});if(!response.ok){const problem=await response.json().catch(()=>({}));setOperationError(problem.detail||"Nội dung file chưa sẵn sàng");return;}const url=URL.createObjectURL(await response.blob());const anchor=document.createElement("a");anchor.href=url;anchor.download=item.name;anchor.click();URL.revokeObjectURL(url);};
  const signIn=(session:SessionData,remember:boolean)=>{persistSession(session,remember);setUser(session.user);setNavigation(null);setApiOnline(true);setAuthenticated(true);};
  const signOut=async()=>{const accessToken=storedToken();if(accessToken)await fetch(`${API_URL}/api/v1/auth/logout`,{method:"POST",headers:{Authorization:`Bearer ${accessToken}`}}).catch(()=>undefined);clearSession();window.history.replaceState(null,"","/");setAuthenticated(false);setPasswordChangeRequired(false);setNavigation(null);setData(null);setUser(null);setLogoutOpen(false);setProfileOpen(false);setView("home");};
  const initials=(user?.displayName||user?.email||"CP").split(/\s+/).map(part=>part[0]).join("").slice(0,2).toUpperCase();const settingsItem=allPages.find(entry=>entry.item.viewKey==="settings")?.item;const businessSection=sections.find(section=>section.key==="business");
  const renderPageButton=(item:NavigationItem,compact=false)=><div className={`nav-entry ${compact?"compact":""}`} key={item.key}><button className={view===item.viewKey?"active":""} onClick={()=>openItem(item)}><span className="nav-icon"><AppIcon name={item.icon} size={17}/></span><span>{item.label}</span></button><button className={`favorite-toggle ${navigation?.favoriteKeys.includes(item.key)?"selected":""}`} aria-label={`${navigation?.favoriteKeys.includes(item.key)?"Bỏ":"Thêm"} yêu thích ${item.label}`} onClick={()=>toggleFavorite(item)}><AppIcon name="star" size={14}/></button></div>;
  const renderSection=(section:NavigationSection)=>{const rootItems=section.items.filter(item=>!item.parentKey);if(section.key==="home"){const home=rootItems.find(item=>item.type==="PAGE");return home?renderPageButton(home):null;}const open=expandedSection===section.key;return <section className={`nav-section ${open?"open":""}`} key={section.key}><button className={`nav-section-trigger ${currentSection?.key===section.key?"active":""}`} aria-expanded={open} onClick={()=>setExpandedSection(open?"":section.key)}><span className="nav-icon"><AppIcon name={section.icon} size={17}/></span><span>{section.label}</span><b><AppIcon name="chevron-down" size={13}/></b></button>{open&&<div className="nav-section-children">{rootItems.map(item=>item.type==="GROUP"?<div className={`nav-group ${expandedGroup===item.key?"open":""}`} key={item.key}><button className="nav-group-trigger" aria-expanded={expandedGroup===item.key} onClick={()=>setExpandedGroup(expandedGroup===item.key?"":item.key)}><span className="nav-icon"><AppIcon name={item.icon} size={17}/></span><span>{item.label}</span><b><AppIcon name="chevron-down" size={13}/></b></button>{expandedGroup===item.key&&<div className="nav-children">{section.items.filter(child=>child.parentKey===item.key&&child.type==="PAGE").map(child=>renderPageButton(child))}</div>}</div>:renderPageButton(item))}{rootItems.length===0&&<span className="nav-section-empty">Chưa có module được cấp quyền</span>}</div>}</section>;};

  if(!authReady)return <div className="auth-loading" aria-label="Đang kiểm tra phiên đăng nhập"><span/></div>;
  if(!authenticated)return <LoginScreen onAuthenticated={signIn}/>;
  if(passwordChangeRequired)return <PasswordChangeScreen onChanged={()=>{setPasswordChangeRequired(false);setNavigation(null);}} onSignOut={()=>void signOut()}/>;
  if(!navigation)return <div className="auth-loading" aria-label="Đang tải Navigation Registry"><span/></div>;

  return <div className="app-shell">
    <aside className={`sidebar ${sidebarOpen?"open":""}`}>
      <div className="brand"><div className="brand-mark"><i/><i/><i/><i/></div><div><strong>Core</strong><span>Platform</span></div></div>
      <nav aria-label="Điều hướng ứng dụng">
        {favoriteEntries.length>0&&<><p>Yêu thích</p>{favoriteEntries.slice(0,5).map(entry=>renderPageButton(entry.item,true))}</>}
        {sections.map(renderSection)}
      </nav>
      <div className="sidebar-status"><div><StatusDot tone={apiOnline?"teal":"amber"}/><strong>{apiOnline?"Backend connected":"Backend unavailable"}</strong></div><span>Navigation {navigation.revision}</span></div>
    </aside>
    {sidebarOpen&&<button className="sidebar-scrim" aria-label="Đóng menu" onClick={()=>setSidebarOpen(false)}/>}
    <div className="main-area"><header className="topbar"><button className="mobile-menu" aria-label="Mở menu" onClick={()=>setSidebarOpen(true)}><AppIcon name="menu"/></button><div className="breadcrumb"><span>{currentSectionLabel}</span><b>/</b><strong>{currentLabel}</strong></div><button className="command-trigger" onClick={()=>setCommandOpen(true)}><AppIcon name="search" size={15}/> Tìm module hoặc chức năng... <kbd>Ctrl K</kbd></button><div className="top-actions"><button aria-label="Thông báo" className="notification-button" onClick={()=>{setNotificationsOpen(!notificationsOpen);setProfileOpen(false);}}><AppIcon name="bell" size={17}/><i/></button><button className="profile-button" aria-expanded={profileOpen} onClick={()=>{setProfileOpen(!profileOpen);setNotificationsOpen(false);}}><span>{initials}</span><div><strong>{user?.displayName||"Người dùng"}</strong><small>{user?.role==="PLATFORM_ADMIN"?"Quản trị viên hệ thống":"Người dùng ứng dụng"}</small></div><b><AppIcon name="chevron-down" size={13}/></b></button></div>
      {notificationsOpen&&<div className="notification-popover"><div><strong>Thông báo</strong><button aria-label="Đóng thông báo" onClick={()=>setNotificationsOpen(false)}><AppIcon name="x" size={16}/></button></div><article><span className="notice teal"><AppIcon name="check-circle" size={15}/></span><p><strong>Navigation Registry đã đồng bộ</strong><small>Menu được lọc theo module, quyền và nhiệm vụ hiện tại.</small></p><time>Live</time></article></div>}
      {profileOpen&&<div className="profile-popover"><div className="profile-summary"><span>{initials}</span><p><strong>{user?.displayName||"Người dùng"}</strong><small>{user?.email}</small></p></div><div className="profile-role"><span>{user?.role==="PLATFORM_ADMIN"?"SYSTEM ADMINISTRATOR":"APPLICATION USER"}</span><em>Dedicated deployment</em></div>{settingsItem&&<button onClick={()=>{openItem(settingsItem);setProfileOpen(false);}}><span><AppIcon name="settings" size={15}/></span> Hồ sơ & bảo mật</button>}<button onClick={()=>setLogoutOpen(true)} className="logout-action"><span><AppIcon name="logout" size={15}/></span> Đăng xuất</button></div>}
    </header><main>{operationError&&<p className="auth-error" role="alert">{operationError}</p>}{view==="home"&&businessSection&&(data?<Overview onNavigate={navigate} data={data} displayName={user?.displayName}/>:<BusinessHome section={businessSection} onOpen={openItem}/>)}{view==="approvals"&&<DemoApprovalWorkspace apiUrl={API_URL}/>} {view==="revenue-intelligence"&&<RevenueIntelligenceWorkspace apiUrl={API_URL}/>} {data&&view==="modules"&&<Modules items={data.modules} onStatus={changeModuleStatus}/>} {data&&view==="resources"&&<Resources items={data.resources} onChanged={refresh}/>} {view==="users"&&<Users/>} {view==="organizations"&&<Organizations/>} {view==="access"&&<Access/>} {data&&view==="activity"&&<Activity items={data.activities}/>} {data&&view==="files"&&<Files items={data.files} storageGb={data.summary.storageGb} onUpload={uploadFile} onDownload={downloadFile}/>} {data&&view==="settings"&&<Settings values={data.settings} onSave={items=>mutate("/api/v1/control-plane/settings","PUT",items)}/>} {currentSection?.key==="system-administration"&&!data&&<div className="auth-loading" aria-label="Đang tải dữ liệu quản trị"><span/></div>}</main></div>
    {commandOpen&&<div className="modal-backdrop" role="presentation" onMouseDown={()=>setCommandOpen(false)}><section className="command-modal" role="dialog" aria-modal="true" aria-label="Tìm chức năng" onMouseDown={event=>event.stopPropagation()}><div className="command-input"><span><AppIcon name="search" size={17}/></span><input autoFocus value={commandQuery} onChange={event=>setCommandQuery(event.target.value)} placeholder="Tìm module hoặc chức năng..."/><kbd>ESC</kbd></div><p>{commandQuery?"Kết quả":"Gần đây và chức năng được cấp quyền"}</p>{commandEntries.slice(0,12).map(entry=><button key={entry.item.key} onClick={()=>openItem(entry.item)}><span><AppIcon name={entry.item.icon}/></span><div><strong>{entry.item.label}</strong><small>{entry.section.label} · {entry.item.ownerModule}</small></div><kbd>→</kbd></button>)}{commandEntries.length===0&&<div className="command-empty">Không tìm thấy chức năng phù hợp với quyền hiện tại.</div>}</section></div>}
    {logoutOpen&&<div className="modal-backdrop logout-backdrop" role="presentation" onMouseDown={()=>setLogoutOpen(false)}><section className="logout-modal" role="dialog" aria-modal="true" aria-labelledby="logout-title" onMouseDown={event=>event.stopPropagation()}><span className="logout-icon"><AppIcon name="logout"/></span><h2 id="logout-title">Đăng xuất khỏi Core Platform?</h2><p>Phiên làm việc hiện tại sẽ kết thúc. Bạn cần xác thực lại để tiếp tục truy cập.</p><div><button className="secondary-button" onClick={()=>setLogoutOpen(false)}>Ở lại</button><button className="danger-button" onClick={signOut}>Đăng xuất</button></div></section></div>}
  </div>;
}
