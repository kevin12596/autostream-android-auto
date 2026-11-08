const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/com/kcliang/autostream/NestedScrollJs.kt', 'utf8');
const script = source.match(/private val SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
function node(overrides = {}) {
  return { scrollTop: 0, scrollLeft: 0, scrollHeight: 1000, clientHeight: 600,
    scrollWidth: 500, clientWidth: 500, overflow: 'auto', parentElement: null,
    getBoundingClientRect: () => ({left: 750, top: 0, width: 250, height: 600, bottom: 600}),
    querySelectorAll: () => [], ...overrides };
}
function fixture(hits = () => [], panels = []) {
  const page = node({scrollHeight: 2400});
  const body = node({overflow: 'visible'});
  const document = {body, documentElement: page, scrollingElement: page,
    elementsFromPoint: hits, querySelectorAll: () => panels};
  const window = {innerWidth: 1000, innerHeight: 600, getComputedStyle: n => ({overflowY:n.overflow})};
  return {page, scroll: vm.runInNewContext(`(${script})`, {window, document})};
}
const list = node();
const item = node({overflow:'visible', parentElement:list});
let f = fixture(() => [item]);
assert.equal(f.scroll(0, 90, null, null), true);
assert.equal(list.scrollTop, 90);
assert.equal(f.page.scrollTop, 0);
list.scrollTop = 400;
f.scroll(0, 90, null, null);
assert.equal(list.scrollTop, 400);
assert.equal(f.page.scrollTop, 0);
f.scroll(0, -1000, null, null);
assert.equal(list.scrollTop, 0);
assert.equal(f.page.scrollTop, 0);
f = fixture();
f.scroll(0, 120, null, null);
assert.equal(f.page.scrollTop, 120);
const left = node(), right = node();
f = fixture(x => [x < 500 ? left : right]);
f.scroll(0, 50, 0.2, 0.5);
assert.equal(left.scrollTop, 50);
assert.equal(right.scrollTop, 0);
const panelList = node();
const panel = node({overflow:'visible', querySelectorAll: () => [panelList]});
f = fixture(() => [], [panel]);
f.scroll(0, 70, null, null);
assert.equal(panelList.scrollTop, 70);
assert.equal(f.page.scrollTop, 0);
assert.equal(f.scroll(0, NaN, null, null), false);
console.log('PASS: nested list targeting, boundary isolation, root fallback, selected anchor, panel lookup and invalid input');

const wrapper = node({getBoundingClientRect: () => ({left:0,top:0,width:1000,height:600,bottom:600})});
const video = node({overflow:'visible',parentElement:wrapper});
list.scrollTop=0;
f=fixture(x => x < 500 ? [video] : [item]);
f.scroll(0,80,0.2,0.5);
assert.equal(wrapper.scrollTop,0);
assert.equal(list.scrollTop,80);
f=fixture(() => [video]);
f.scroll(0,80,null,null);
assert.equal(wrapper.scrollTop,80);
console.log('PASS: full-width page wrapper cannot steal playlist scroll; wrapper fallback still works');
