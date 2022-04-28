import template from "./aggregate-overlay-diagram-section.html";
import {initialiseData} from "../../../common";
import AggregateOverlayDiagramPanel from "../panel/AggregateOverlayDiagramPanel.svelte";


const bindings = {
    parentEntityRef: "<"
};

const initialState = {
    AggregateOverlayDiagramPanel
};


function controller() {

    const vm = initialiseData(this, initialState);

    vm.$onInit = () => {
        console.log({ref: vm.parentEntityRef});
    }

}

controller.$inject = [];


const component = {
    bindings,
    controller,
    template
}


export default {
    component,
    id: "waltzAggregateOverlayDiagramSection"
}