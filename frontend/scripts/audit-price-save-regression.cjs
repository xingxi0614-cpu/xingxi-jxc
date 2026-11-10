// Run the actual Vue save handlers with failed advisory requests; no browser/API writes.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const assert = require('node:assert/strict'), ts = require('typescript');
const root = path.resolve(__dirname, '..');
function functions(file, names) {
  let source = fs.readFileSync(path.join(root, file), 'utf8');
  if (file.endsWith('.vue')) source = source.split('<script setup lang="ts">')[1].split('</script>')[0];
  const ast = ts.createSourceFile('source.ts', source, ts.ScriptTarget.Latest, true);
  const selected = ast.statements.filter(s => ts.isFunctionDeclaration(s) && names.includes(s.name?.text));
  assert.equal(selected.length, names.length, `Missing function in ${file}`);
  return ts.transpileModule(selected.map(s => s.getText(ast)).join('\n').replace(/^export /gm, ''), {compilerOptions: {target: ts.ScriptTarget.ES2022}}).outputText;
}
(async () => {
  let checks = 0;
  for (const entry of ['desktopDraft','desktopSubmit','mobileDraft','mobileSubmit']) {
    for (const fault of ['network',4001,4003,401,403]) {
      let saves = 0, submits = 0;
      const error = Object.assign(new Error('synthetic reference failure'), typeof fault === 'string' ? {} : fault > 1000 ? {code:fault} : {response:{status:fault}});
      const context = {
        form:{supplierId:5,warehouseId:1,docDate:'2026-09-30',orderDate:'2026-09-30',lines:[{materialId:1,qty:1,amount:113,price:113,taxRate:13}]},
        refs:{value:{}},priceRefs:{value:{}},saving:{value:false},poAttRef:{value:null},formVisible:{value:true},
        validateForm:()=>'',roundMoney:n=>Math.round(n*100)/100,load:async()=>{},fmtMoney:String,
        PRICE_CHECK_CODE:4220,PRICE_LOW_RATIO:0.2,PRICE_HIGH_RATIO:5,
        poApi:{priceRef:async()=>{throw error},create:async()=>{saves++;await Promise.resolve();return{id:1}},submit:async()=>{submits++}},
        ElMessage:{warning:()=>{},success:()=>{}},router:{back:()=>{}}
      };
      vm.createContext(context);
      vm.runInContext(functions('src/utils/poPriceCheck.ts',['fetchPriceRefs','fetchPriceRefsForSave','priceDeviation','findPriceIssues','saveWithPriceNote']),context);
      const desktop = entry.startsWith('desktop');
      vm.runInContext(desktop ? functions('src/views/purchase/po/list.vue',['runPriceCheck','save']) : functions('src/views/mobile/PurchaseOrder.vue',['buildPayload','priceCheck','zeroPriceLine','saveDraft','submit']),context);
      const call = () => desktop ? context.save(entry.endsWith('Submit')) : entry.endsWith('Submit') ? context.submit() : context.saveDraft();
      // Same-turn double click must not create a second order while the precheck awaits.
      await Promise.all([call(),call()]);
      assert.equal(saves,fault==='network'?1:0,`${entry}/${fault}: advisory vs authorization failure`);
      assert.equal(submits,fault==='network'&&entry.endsWith('Submit')?1:0);
      assert.equal(context.saving.value,false,'Retry must remain possible');checks++;
    }
  }
  console.log(`PRICE-SAVE-OK: ${checks} cases (desktop/mobile draft/submit; fault fallback, auth rejection, double click, retry state)`);
})().catch(e=>{console.error(e);process.exitCode=1});
