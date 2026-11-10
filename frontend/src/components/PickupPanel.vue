<template>
  <!--
    2026-10-17：直接走材料出库的申请（DIRECT）没有分次领料可看，整块不显示——以前这里只剩一句提示，
    外面还套着一圈框线，详情里显得又空又乱（用户截图）。读取中也先不占位，读到了再出来，免得闪一下又消失。
    样式与详情弹窗其它小节一致：小标题 + 内容，不再单独套框。
  -->
  <section v-if="error || (data && data.mode !== 'DIRECT')" class="pickup-panel" v-loading="loading">
    <div class="pickup-heading"><div><h3>领料进度</h3><p>拿多少记多少，剩下的到期确认还要不要。</p></div><el-button link type="primary" @click="reload" :disabled="busy">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="data && !error">
      <el-alert v-if="data.mode === 'DIRECT'" title="此申请已用于材料出库，请继续原出库流程，不能再转库重复领取。" type="info" :closable="false" />
      <template v-else>
        <el-alert v-if="data.ended" title="本申请已结束：实际领走和不再领取的数量均已记录。" type="success" :closable="false" />
        <p v-else-if="data.dueDate" :class="{ overdue: data.overdue }">{{ data.overdue ? '已到期，请处理剩余未领' : '剩余材料确认期限' }}：{{ data.dueDate }}<span v-if="data.extensions"> · 已延期 {{ data.extensions }} 次</span></p>
        <el-alert v-if="data.mode === 'LEGACY'" title="这是旧申请。请先核清过去的转库记录，已经领走的不能重复登记；账实差异须先更正。" type="warning" :closable="false" />
        <el-checkbox v-if="data.mode === 'LEGACY' && !data.ended" v-model="legacyChecked">已核清历史，这些剩余材料尚未转出</el-checkbox>
        <div class="pickup-lines">
          <!-- 10-20 用户截图：卡片白底、规格长的那张折成两行，几张卡片里的数字上下对不齐。
               改为：卡片头（表头同色底）放名称一行、规格一行，放不下省略、悬停看全；下面数字按「名称在上、数在下」排成等宽格子。 -->
          <article v-for="line in data.lines" :key="line.id" class="pickup-line">
            <header class="pickup-line-head"><strong :title="line.materialName">{{ line.materialName }}</strong><small :title="line.spec">{{ line.spec || '\u00a0' }}</small></header>
            <dl class="pickup-numbers">
              <div><dt>申请</dt><dd>{{ fmt(line.qty) }} <small>{{ line.unit }}</small></dd></div>
              <div><dt>已领</dt><dd>{{ fmt(line.transferredQty) }}</dd></div>
              <div><dt>不再领</dt><dd>{{ fmt(line.closedQty) }}</dd></div>
              <div v-if="Number(line.busyQty)"><dt>在办理</dt><dd>{{ fmt(line.busyQty) }}</dd></div>
              <div class="avail"><dt>还可领</dt><dd>{{ fmt(line.availableQty) }}</dd></div>
            </dl>
            <label v-if="action === 'pickup' && Number(line.availableQty)>0">本次准备领多少
              <el-input-number v-model="quantities[line.id]" :min="0" :max="Number(line.availableQty)" :precision="4" controls-position="right" :aria-label="line.materialName + '本次领取数量'" />
            </label>
            <el-checkbox v-if="action === 'close' && Number(line.availableQty)>0" v-model="selected[line.id]" :disabled="Number(line.busyQty)>0">这项剩下的 {{ fmt(line.availableQty) }} {{ line.unit }} 不领了</el-checkbox>
          </article>
        </div>
        <el-alert v-if="pendingClose" :title="'剩余不领待仓管确认：' + pendingClose.reason" type="warning" :closable="false" />
        <div v-if="pendingClose && data.canWarehouse" class="pickup-actions"><el-button type="primary" :loading="busy" @click="review(true)">核实无在办交接，确认关闭</el-button><el-button :disabled="busy" @click="review(false)">退回说明</el-button></div>
        <div v-if="!data.ended && !pendingClose && !action" class="pickup-actions">
          <el-button v-if="data.canWarehouse" type="primary" :disabled="!legacyReady || !hasAvailable" @click="begin('pickup')">登记本次领料</el-button>
          <el-button v-if="data.canDecide" :disabled="!legacyReady" @click="begin('extend')">还要领，定个日期</el-button>
          <el-button v-if="data.canDecide" :disabled="!legacyReady || !hasAvailable" @click="begin('close')">剩下的不领了</el-button>
        </div>
        <el-form v-if="action" class="pickup-form" label-position="top" @submit.prevent>
          <template v-if="action === 'pickup'">
            <el-form-item label="领到哪个仓库"><el-select v-model="toWarehouse" filterable placeholder="请选择实际接收仓库"><el-option v-for="w in warehouses.filter(w=>w.id !== sourceWarehouse)" :key="w.id" :value="w.id" :label="w.name" /></el-select></el-form-item>
            <p>本次数量将提交现有转库审批；审批通过后，再按实际交接量确认。</p>
          </template>
          <el-form-item v-if="action === 'extend'" label="预计哪天领"><el-date-picker v-model="dueDate" type="date" value-format="YYYY-MM-DD" placeholder="选择领取日期" /></el-form-item>
          <el-form-item v-if="action === 'close' || (action === 'extend' && data.leader)" :label="action === 'close' ? '不再领取的原因' : '继续延期的原因'"><el-input v-model="reason" maxlength="200" placeholder="例如：工程用量减少 / 等待施工安排" /></el-form-item>
          <div class="pickup-actions"><el-button type="primary" :loading="busy" @click="save">{{ action === 'pickup' ? '提交本次领料' : action === 'close' ? '交仓管确认' : '确认继续领取' }}</el-button><el-button :disabled="busy" @click="action=''">取消</el-button></div>
        </el-form>
        <div v-if="data.transfers.length" class="pickup-records"><h4>每次领料记录</h4>
          <div v-for="t in data.transfers" :key="t.id" class="pickup-record"><div><strong>{{ t.docNo }}</strong><p>{{ transferLabel(t.status) }}<span v-if="t.receiver"> · 接收人 {{ t.receiver }}</span></p></div><div class="pickup-actions">
            <el-button v-if="t.status === 'READY' && data.canWarehouse" type="primary" :disabled="busy" @click="openHandover(t)">确认实际交接</el-button>
            <el-button v-if="['DRAFT','REJECTED','READY'].includes(t.status) && data.canWarehouse" :disabled="busy" @click="cancelTransfer(t)">本次不领了</el-button>
          </div></div>
        </div>
        <div v-if="handover" class="pickup-form"><h4>实际交接：{{ handover.docNo }}</h4><p>双方当场核对后确认。没有实际交接的不要确认；数量可少于本次批准量。</p>
          <label v-for="l in handover.lines" :key="l.id" class="handover-line">{{ data.lines.find((x:any)=>x.id === l.sourceReqLineId)?.materialName }}<el-input-number v-model="l.actual" :min="0" :max="Number(l.qty)" :precision="4" controls-position="right" /></label>
          <el-input v-model="receiver" placeholder="实际接收人" maxlength="100" aria-label="实际接收人" />
          <div class="pickup-actions"><el-button type="primary" :loading="busy" @click="confirmHandover">确认已交接，更新库存</el-button><el-button :disabled="busy" @click="handover=null">返回</el-button></div>
        </div>
        <el-collapse v-if="data.events.length"><el-collapse-item title="查看延期与处理记录" name="history"><div v-for="e in data.events" :key="e.id" class="pickup-history">{{ e.created_at }} · {{ e.actorName || '系统' }} · {{ eventLabel(e.event_type) }} · {{ eventStatus(e.status) }}<p>{{ e.reason }}<span v-if="e.review_reason"> · 核实说明 {{ e.review_reason }}</span><span v-if="e.due_date"> · 到 {{ e.due_date }}</span><span v-if="e.reviewerName"> · 确认人 {{ e.reviewerName }}</span></p></div></el-collapse-item></el-collapse>
      </template>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { outApi, inventoryApi, masterApi } from '@/api'
const props=defineProps<{requestId:number;sourceWarehouse?:number}>()
const emit=defineEmits<{changed:[]}>()
const data=ref<any>(null),loading=ref(false),busy=ref(false),error=ref(''),action=ref(''),legacyChecked=ref(false)
const quantities=ref<Record<number,number>>({}),selected=ref<Record<number,boolean>>({}),warehouses=ref<any[]>([]),toWarehouse=ref<number>(),dueDate=ref(''),reason=ref(''),handover=ref<any>(null),receiver=ref('')
const pendingClose=computed(()=>data.value?.events.find((e:any)=>e.event_type==='CLOSE' && e.status==='PENDING'))
const legacyReady=computed(()=>data.value?.mode!=='LEGACY' || legacyChecked.value)
const hasAvailable=computed(()=>data.value?.lines.some((l:any)=>Number(l.availableQty)>0))
const fmt=(n:any)=>Number(n||0).toLocaleString('zh-CN',{maximumFractionDigits:4})
const transferLabel=(s:string)=>({DRAFT:'草稿',PENDING:'待审批',READY:'待实际交接',APPROVED:'已交接',REJECTED:'已退回',CANCELLED:'本次已取消'}[s]||s)
const eventLabel=(s:string)=>({ENABLE:'开始分批领料',PICKUP:'登记领料',HANDOVER:'实际交接',EXTEND:'继续领取',CLOSE:'剩余不领',CANCEL_PICKUP:'取消本次领料'}[s]||s)
const eventStatus=(s:string)=>({PENDING:'待确认',APPROVED:'已确认',REJECTED:'已退回',RECORDED:'已记录'}[s]||s)
async function reload(){loading.value=true;error.value='';try{data.value=await outApi.pickupDetail(props.requestId)}catch(e:any){error.value=e.message||'领料记录加载失败，请重试'}finally{loading.value=false}}
watch(()=>props.requestId,()=>{action.value='';handover.value=null;data.value=null;legacyChecked.value=false;reload()},{immediate:true})
async function begin(a:string){action.value=a;reason.value='';dueDate.value='';quantities.value={};selected.value={};if(a==='pickup')warehouses.value=await masterApi.warehouses() as any[]}
async function perform(fn:()=>Promise<any>,msg:string){if(busy.value)return;busy.value=true;try{await fn();ElMessage.success(msg);action.value='';handover.value=null;await reload();emit('changed')}finally{busy.value=false}}
async function save(){
 if(action.value==='pickup'){
  const lines=data.value.lines.filter((l:any)=>Number(quantities.value[l.id])>0).map((l:any)=>({reqLineId:l.id,qty:Number(quantities.value[l.id])}))
  if(!toWarehouse.value || !lines.length){ElMessage.warning('请选择接收仓库，填写至少一项本次领取数量');return}
  await perform(()=>outApi.pickupCreate(props.requestId,{toWarehouseId:toWarehouse.value,lines,legacyChecked:legacyChecked.value}),'已提交本次领料，审批通过后确认实际交接')
 }else if(action.value==='extend'){
  if(!dueDate.value){ElMessage.warning('请选择预计领取日期');return}
  await perform(()=>outApi.pickupExtend(props.requestId,{dueDate:dueDate.value,reason:reason.value,legacyChecked:legacyChecked.value}),'已记录下次领取日期')
 }else{
  const lineIds=data.value.lines.filter((l:any)=>selected.value[l.id]).map((l:any)=>l.id)
  if(!lineIds.length || !reason.value.trim()){ElMessage.warning('请选择不再领取的物料并填写原因');return}
  await perform(()=>outApi.pickupClose(props.requestId,{lineIds,reason:reason.value,legacyChecked:legacyChecked.value}),'已交仓管确认，确认后结束对应余量')
 }
}
async function review(approve:boolean){let r='';if(!approve){try{r=(await ElMessageBox.prompt('请说明需要重新核实的原因','退回剩余不领',{inputValidator:v=>!!v?.trim() || '请填写原因'})).value}catch{return}}
 await perform(()=>outApi.pickupReview(props.requestId,pendingClose.value.id,{approve,reason:r}),approve?'剩余不领已确认':'已退回申请人处理')}
async function openHandover(t:any){const d:any=await inventoryApi.transferDetail(t.id);handover.value={...d,lines:d.lines.map((l:any)=>({...l,actual:Number(l.qty)}))};receiver.value=d.transferer||''}
async function confirmHandover(){if(!receiver.value.trim()){ElMessage.warning('请填写实际接收人');return}await perform(()=>inventoryApi.transferHandover(handover.value.id,{receiver:receiver.value,lines:handover.value.lines.map((l:any)=>({reqLineId:l.sourceReqLineId,qty:l.actual}))}),'实际交接已记录，库存与申请进度已更新')}
async function cancelTransfer(t:any){let r='';try{r=(await ElMessageBox.prompt('本次为什么没有领取？','取消未交接领料',{inputValidator:v=>!!v?.trim() || '请填写原因'})).value}catch{return}await perform(()=>inventoryApi.transferCancelPickup(t.id,{reason:r}),'本次领料已取消，数量回到待领余量')}
</script>
<style scoped>
.pickup-panel{margin:0;padding:0;color:var(--el-text-color-primary);min-width:0}.pickup-heading h3{font-size:14px;font-weight:600;color:var(--table-header-text);margin:0}.pickup-heading p{margin:2px 0 0;font-size:12px}.pickup-heading,.pickup-record{display:flex;justify-content:space-between;gap:12px;align-items:center}.pickup-panel h3,.pickup-panel h4{margin:0 0 8px}.pickup-panel p{margin:6px 0 12px;color:var(--el-text-color-secondary);line-height:1.6}.pickup-lines{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,260px),1fr));gap:12px;margin:10px 0}.pickup-line{border:1px solid var(--table-grid);border-radius:8px;overflow:hidden;min-width:0;background:var(--bg-panel);display:flex;flex-direction:column}.pickup-line-head{background:var(--table-header-bg);padding:10px 14px;display:flex;flex-direction:column;gap:2px;min-width:0}.pickup-line-head strong,.pickup-line-head small{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.pickup-line-head strong{color:var(--table-header-text);font-size:14px}.pickup-line-head small{color:var(--text-2);font-size:12px}.pickup-numbers{display:grid;grid-template-columns:repeat(auto-fit,minmax(56px,1fr));margin:0;padding:12px 14px;gap:8px}.pickup-numbers div{min-width:0}.pickup-numbers dt{font-size:12px;color:var(--text-2)}.pickup-numbers dd{margin:2px 0 0;font-size:15px;font-weight:600;font-variant-numeric:tabular-nums;white-space:nowrap}.pickup-numbers dd small{font-size:12px;font-weight:400;color:var(--text-2)}.pickup-numbers .avail dd{color:var(--color-primary)}.pickup-line label,.pickup-line>.el-checkbox{margin:0 14px 12px}.pickup-line label,.handover-line{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:10px}.handover-line{margin:10px 0}.pickup-actions{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}.pickup-actions :deep(.el-button+.el-button){margin-left:0}.pickup-form{padding:14px;border-radius:8px;background:var(--el-fill-color-light);margin:12px 0}.pickup-record{border-top:1px solid var(--el-border-color-lighter);padding:10px 0}.pickup-record strong{overflow-wrap:anywhere}.pickup-history{padding:8px 0;border-bottom:1px solid var(--el-border-color-lighter)}.pickup-panel .overdue{color:var(--el-color-danger)}.pickup-panel :deep(.el-checkbox){white-space:normal;height:auto;min-height:36px}.pickup-panel :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.pickup-panel :deep(.el-select),.pickup-panel :deep(.el-date-editor){max-width:100%;width:100%}.pickup-panel :deep(.el-alert){margin:10px 0}.pickup-panel :deep(.el-button){white-space:normal;height:auto;min-height:36px}
@media(max-width:600px){.pickup-record{display:block}.pickup-panel :deep(.el-button){min-height:44px}.pickup-actions>*{flex:1 1 auto}.pickup-panel :deep(.el-input-number){width:150px}.pickup-heading{align-items:flex-start}.pickup-heading h3{font-size:17px}}
</style>
