/*
 * Waltz - Enterprise Architecture
 * Copyright (C) 2016, 2017, 2018, 2019 Waltz open source project
 * See README.md for more information
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific
 *
 */


import template from "./playpen1.html";
import {initialiseData, randomPick} from "../../common";
import Grid from "./Grid.svelte";
import _ from "lodash";
import {characters} from "./data";

const initData = {
    Grid,
    // parentEntityRef: {id: 20768, kind: "APPLICATION"}
    parentEntityRef: {id: 6538029, kind: "CHANGE_INITIATIVE"},
    measurableEntityRef: {id: 54566, kind: "MEASURABLE"}
};


function controller($element, $q, serviceBroker) {

    const vm = initialiseData(this, initData);



    vm.rowData = _
        .chain(_.range(0, 4000))
        .map(d => ({c1: d, c2: d + " name", c3: randomPick(characters)}))
        .value();

    vm.columnDefs = [
        { field: "c1", name: "c1" },
        { field: "c2", name: "c2" },
        { field: "c3", name: "c3" }
    ]
}

controller.$inject = ["$element", "$q", "ServiceBroker"];

const view = {
    template,
    controller,
    controllerAs: "$ctrl",
    bindToController: true,
    scope: {}
};

export default view;