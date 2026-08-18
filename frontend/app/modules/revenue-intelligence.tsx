"use client";

import { useCallback, useEffect, useState } from "react";
import { AppIcon } from "../components/app-icon";

type Breakdown = { key:string; label:string; revenue:number; spend:number; orders:number };
type Dashboard = {
  from:string; to:string;
  kpis:{ adSpend:number; netRevenue:number; paidAttributedRevenue:number; roas:number|null; mer:number|null; orders:number; customers:number; repeatRate:number };
  channels:Breakdown[]; lifecycle:Breakdown[]; businessModels:Breakdown[];
  dataQuality:{ totalOrders:number; matchedOrders:number; matchCoverage:number; warning:boolean; message:string };
};
type Batch = { id:string; dataset:string; sourceName:string; status:string; totalRows:number; acceptedRows:number; rejectedRows:number; startedAt:string };
type Reconciliation = { grossRevenue:number; discounts:number; returns:number; cancellations:number; shipping:number; tax:number; computedNetRevenue:number; storedNetRevenue:number; variance:number };
type Customer = { id:string; sourceSystem:string; externalId:string; fullName:string; emailMasked?:string; phoneMasked?:string; historyComplete:boolean; lifecycle:string; validOrderCount:number; lastOrderAt?:string };
type Tab = "dashboard"|"imports"|"customers"|"reconciliation";

const DATASETS = [
  {key:"customers",label:"Khách hàng",icon:"users"},
  {key:"orders",label:"Đơn hàng",icon:"store"},
  {key:"ad-spend",label:"Chi phí quảng cáo",icon:"megaphone"},
  {key:"touchpoints",label:"Điểm chạm",icon:"route"},
];

function token() {
  if (typeof window === "undefined") return "";
  return window.localStorage.getItem("core-access-token") || window.sessionStorage.getItem("core-access-token") || "";
}

async function request<T>(apiUrl:string,path:string,init:RequestInit={}):Promise<T> {
  const response=await fetch(`${apiUrl}${path}`,{...init,headers:{Authorization:`Bearer ${token()}`,...init.headers}});
  if(!response.ok){const problem=await response.json().catch(()=>({}));throw new Error(problem.detail||"Không thể tải dữ liệu Revenue Intelligence.");}
  return response.json() as Promise<T>;
}

function dateValue(date:Date){return date.toISOString().slice(0,10);}
function money(value:number){return new Intl.NumberFormat("vi-VN",{style:"currency",currency:"VND",maximumFractionDigits:0}).format(value||0);}
function number(value:number){return new Intl.NumberFormat("vi-VN").format(value||0);}
function ratio(value:number|null){return value===null?"—":`${new Intl.NumberFormat("vi-VN",{maximumFractionDigits:2}).format(value)}x`;}

export default function RevenueIntelligenceWorkspace({apiUrl}:{apiUrl:string}){
  const today=new Date();const ninetyDaysAgo=new Date(today);ninetyDaysAgo.setDate(today.getDate()-89);
  const [from,setFrom]=useState(dateValue(ninetyDaysAgo));const [to,setTo]=useState(dateValue(today));const [tab,setTab]=useState<Tab>("dashboard");
  const [dashboard,setDashboard]=useState<Dashboard|null>(null);const [batches,setBatches]=useState<Batch[]>([]);const [customers,setCustomers]=useState<Customer[]>([]);const [reconciliation,setReconciliation]=useState<Reconciliation|null>(null);
  const [loading,setLoading]=useState(true);const [working,setWorking]=useState("");const [message,setMessage]=useState("");

  const load=useCallback(async()=>{setLoading(true);setMessage("");try{const query=`from=${from}&to=${to}`;const [summary,imports,people,reconcile]=await Promise.all([
    request<Dashboard>(apiUrl,`/api/v1/revenue-intelligence/dashboard?${query}`),request<Batch[]>(apiUrl,"/api/v1/revenue-intelligence/imports"),request<Customer[]>(apiUrl,"/api/v1/revenue-intelligence/customers"),request<Reconciliation>(apiUrl,`/api/v1/revenue-intelligence/reconciliation?${query}`)
  ]);setDashboard(summary);setBatches(imports);setCustomers(people);setReconciliation(reconcile);}catch(error){setMessage(error instanceof Error?error.message:"Không thể tải dữ liệu");}finally{setLoading(false);}},[apiUrl,from,to]);
  useEffect(()=>{void load();},[load]);

  const upload=async(dataset:string,file?:File)=>{if(!file)return;setWorking(dataset);setMessage("");try{const form=new FormData();form.append("file",file);const result=await request<{acceptedRows:number;rejectedRows:number;duplicate:boolean}>(apiUrl,`/api/v1/revenue-intelligence/imports/${dataset}`,{method:"POST",body:form});setMessage(result.duplicate?"Tệp đã được nhập trước đó; hệ thống không tạo dữ liệu trùng.":`Đã nhận ${result.acceptedRows} dòng, từ chối ${result.rejectedRows} dòng.`);await load();}catch(error){setMessage(error instanceof Error?error.message:"Import thất bại");}finally{setWorking("");}};
  const rebuild=async()=>{setWorking("attribution");setMessage("");try{const result=await request<{ordersProcessed:number;resultsWritten:number}>(apiUrl,`/api/v1/revenue-intelligence/attribution/rebuild?from=${from}&to=${to}`,{method:"POST"});setMessage(`Đã tính ${result.resultsWritten} kết quả attribution cho ${result.ordersProcessed} đơn hàng.`);await load();}catch(error){setMessage(error instanceof Error?error.message:"Không thể tính attribution");}finally{setWorking("");}};

  const maxRevenue=Math.max(1,...(dashboard?.channels.map(item=>item.revenue)??[1]));
  return <div className="ri-workspace">
    <div className="page-heading"><div><p className="eyebrow">Revenue Intelligence</p><h1>Hiệu quả Marketing & Doanh thu</h1><p className="page-description">Theo dõi nguồn khách, chi phí quảng cáo, doanh thu và tỷ lệ mua lại trên một bộ dữ liệu đã đối soát.</p></div><div className="ri-period"><label>Từ<input type="date" value={from} onChange={event=>setFrom(event.target.value)}/></label><label>Đến<input type="date" value={to} onChange={event=>setTo(event.target.value)}/></label><button className="secondary-button" onClick={()=>void load()}><AppIcon name="refresh" size={15}/> Làm mới</button></div></div>
    <div className="ri-tabs" role="tablist">{([['dashboard','Tổng quan'],['imports','Nhập dữ liệu'],['customers','Khách hàng'],['reconciliation','Đối soát']] as [Tab,string][]).map(([key,label])=><button key={key} className={tab===key?"active":""} onClick={()=>setTab(key)}>{label}</button>)}</div>
    {message&&<div className="ri-message"><AppIcon name="check-circle" size={17}/><span>{message}</span><button aria-label="Đóng" onClick={()=>setMessage("")}><AppIcon name="x" size={14}/></button></div>}
    {loading?<div className="auth-loading ri-loading" aria-label="Đang tải Revenue Intelligence"><span/></div>:<>
      {tab==="dashboard"&&dashboard&&<>
        <div className={`ri-quality ${dashboard.dataQuality.warning?"warning":"good"}`}><AppIcon name={dashboard.dataQuality.warning?"activity":"check-circle"} size={18}/><div><strong>Độ phủ định danh {dashboard.dataQuality.matchCoverage}%</strong><span>{dashboard.dataQuality.message} · {dashboard.dataQuality.matchedOrders}/{dashboard.dataQuality.totalOrders} đơn đã ghép khách.</span></div><button onClick={()=>setTab("imports")}>Kiểm tra dữ liệu →</button></div>
        <section className="ri-kpis">
          <article><span><AppIcon name="megaphone"/></span><small>Chi phí quảng cáo</small><strong>{money(dashboard.kpis.adSpend)}</strong><em>Trong kỳ đã chọn</em></article>
          <article><span><AppIcon name="chart"/></span><small>Doanh thu thuần</small><strong>{money(dashboard.kpis.netRevenue)}</strong><em>{number(dashboard.kpis.orders)} đơn hàng</em></article>
          <article><span><AppIcon name="zap"/></span><small>ROAS</small><strong>{ratio(dashboard.kpis.roas)}</strong><em>Doanh thu paid attribution</em></article>
          <article><span><AppIcon name="activity"/></span><small>MER</small><strong>{ratio(dashboard.kpis.mer)}</strong><em>Toàn bộ doanh thu / quảng cáo</em></article>
          <article><span><AppIcon name="repeat"/></span><small>Tỷ lệ khách mua lại</small><strong>{dashboard.kpis.repeatRate}%</strong><em>{number(dashboard.kpis.customers)} khách có đơn</em></article>
        </section>
        <div className="ri-grid">
          <section className="panel ri-channel"><div className="panel-header"><div><h2>Doanh thu theo nguồn</h2><p>Last non-direct · cửa sổ 30 ngày</p></div><button className="ghost-button" disabled={working==="attribution"} onClick={()=>void rebuild()}>{working==="attribution"?"Đang tính...":"Tính lại attribution"}</button></div>
            <div className="ri-bars">{dashboard.channels.map(item=><div key={item.key}><div><strong>{item.label}</strong><span>{money(item.revenue)}</span></div><i><b style={{width:`${Math.max(2,item.revenue/maxRevenue*100)}%`}}/></i><small>{number(item.orders)} đơn · chi phí {money(item.spend)}</small></div>)}{dashboard.channels.length===0&&<p className="ri-empty">Chưa có kết quả attribution. Nhập dữ liệu và chọn “Tính lại attribution”.</p>}</div>
          </section>
          <section className="panel ri-structure"><div className="panel-header"><div><h2>Cơ cấu doanh thu</h2><p>Khách hàng và mô hình bán</p></div></div><h3>Khách mới / quay lại</h3>{dashboard.lifecycle.map(item=><div className="ri-split" key={item.key}><span>{item.label}</span><strong>{money(item.revenue)}</strong><small>{number(item.orders)} đơn</small></div>)}<h3>Bán buôn / bán lẻ</h3>{dashboard.businessModels.map(item=><div className="ri-split" key={item.key}><span>{item.label}</span><strong>{money(item.revenue)}</strong><small>{number(item.orders)} đơn</small></div>)}</section>
        </div>
      </>}
      {tab==="imports"&&<div className="ri-grid ri-import-layout"><section className="panel"><div className="panel-header"><div><h2>Nhập dữ liệu CSV</h2><p>UTF-8 · tối đa 20 MB · tự chống nhập trùng theo checksum</p></div></div><div className="ri-upload-grid">{DATASETS.map(dataset=><label key={dataset.key} className="ri-upload"><span><AppIcon name={dataset.icon}/></span><div><strong>{dataset.label}</strong><small>{working===dataset.key?"Đang xử lý...":"Chọn tệp CSV"}</small></div><input type="file" accept=".csv,text/csv" disabled={Boolean(working)} onChange={event=>{void upload(dataset.key,event.target.files?.[0]);event.currentTarget.value="";}}/></label>)}</div></section><section className="panel"><div className="panel-header"><div><h2>Lịch sử import</h2><p>50 batch gần nhất</p></div></div><div className="ri-batches">{batches.map(batch=><div key={batch.id}><span className={`state ${batch.status.includes("ERROR")?"attention":"healthy"}`}>{batch.status}</span><div><strong>{batch.dataset}</strong><small>{batch.sourceName} · {new Date(batch.startedAt).toLocaleString("vi-VN")}</small></div><b>{batch.acceptedRows}/{batch.totalRows}<small>hợp lệ</small></b></div>)}{batches.length===0&&<p className="ri-empty">Chưa có batch import.</p>}</div></section></div>}
      {tab==="customers"&&<section className="panel"><div className="panel-header"><div><h2>Hồ sơ khách hàng hợp nhất</h2><p>Chỉ hiển thị PII đã che; matching dùng hash email/điện thoại.</p></div><span className="live-pill">{customers.length} hồ sơ</span></div><div className="table-wrap"><table><thead><tr><th>Khách hàng</th><th>Nguồn / mã</th><th>Liên hệ đã che</th><th>Vòng đời</th><th>Đơn hợp lệ</th><th>Mua gần nhất</th></tr></thead><tbody>{customers.map(customer=><tr key={customer.id}><td><strong>{customer.fullName||"Chưa có tên"}</strong></td><td>{customer.sourceSystem}<small>{customer.externalId}</small></td><td>{customer.emailMasked||"—"}<small>{customer.phoneMasked||""}</small></td><td><span className={`state ${customer.lifecycle==="UNKNOWN"?"attention":"healthy"}`}>{customer.lifecycle}</span></td><td>{customer.validOrderCount}</td><td>{customer.lastOrderAt?new Date(customer.lastOrderAt).toLocaleDateString("vi-VN"):"—"}</td></tr>)}</tbody></table></div>{customers.length===0&&<p className="ri-empty">Chưa có dữ liệu khách hàng.</p>}</section>}
      {tab==="reconciliation"&&reconciliation&&<div className="ri-grid"><section className="panel"><div className="panel-header"><div><h2>Cầu nối doanh thu</h2><p>Doanh thu gộp trừ toàn bộ khoản giảm trừ</p></div></div><div className="ri-reconcile"><div><span>Doanh thu gộp</span><strong>{money(reconciliation.grossRevenue)}</strong></div><div><span>− Chiết khấu</span><strong>{money(reconciliation.discounts)}</strong></div><div><span>− Hoàn trả</span><strong>{money(reconciliation.returns)}</strong></div><div><span>− Hủy đơn</span><strong>{money(reconciliation.cancellations)}</strong></div><div className="total"><span>= Doanh thu thuần</span><strong>{money(reconciliation.computedNetRevenue)}</strong></div></div></section><section className={`panel ri-variance ${Math.abs(reconciliation.variance)>0.009?"warning":"good"}`}><span><AppIcon name={Math.abs(reconciliation.variance)>0.009?"activity":"check-circle"} size={28}/></span><h2>{Math.abs(reconciliation.variance)>0.009?"Có chênh lệch":"Đối soát nội bộ khớp"}</h2><strong>{money(reconciliation.variance)}</strong><p>Chênh lệch giữa công thức chuẩn và `net_revenue` đã lưu.</p><small>Thuế {money(reconciliation.tax)} · vận chuyển {money(reconciliation.shipping)} được báo cáo riêng, không cộng vào doanh thu thuần.</small></section></div>}
    </>}
  </div>;
}
