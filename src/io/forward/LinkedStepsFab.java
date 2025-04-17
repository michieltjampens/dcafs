package io.forward;

import util.data.RealtimeValues;
import util.data.ValStoreFab;
import util.xml.XMLdigger;

import java.util.ArrayList;

public class LinkedStepsFab {

    public static AbstractStep[] buildLink(XMLdigger dig, RealtimeValues rtvals, String delimiter) {
        var ft = new FabTools(rtvals, delimiter);

        ft.id = dig.attr("id", "");

        addSteps(dig, ft, null);
        return ft.steps.toArray(AbstractStep[]::new);
    }

    private static void addSteps(XMLdigger steps, FabTools ft, AbstractStep parent) {

        var rtvals = ft.rtvals;
        var delimiter = ft.delimiter;

        for (var step : steps.digOut("*")) {

            if (!step.currentTrusted().hasAttribute("delimiter"))
                step.currentTrusted().setAttribute("delimiter", delimiter);

            switch (step.tagName("")) {
                case "if" -> {
                    var filterOpt = FilterStepFab.buildFilterStep(step, delimiter, rtvals);
                    if (filterOpt.isPresent()) {
                        var filter = filterOpt.get();
                        if (parent == null) {
                            parent = filter;
                            ft.addStep(parent);
                            addSteps(step, ft, parent);// Add all sub steps
                            parent = parent.getLastStep(); // Point to last sub
                        } else { // Nested?
                            parent.setNext(filter);
                            ft.setIfFailure(filter);
                            addSteps(step, ft, filter);
                        }
                        ft.overwriteLastIf(filter);
                        continue;
                    }
                }
                case "filter" -> {
                    var filterOpt = FilterStepFab.buildFilterStep(step, delimiter, rtvals);
                    if (filterOpt.isEmpty())
                        return;
                    parent = addToOrMakeParent(filterOpt.get(), parent, ft);
                }
                case "math" -> {
                    var mfOpt = StepFab.buildMathStep(step, delimiter, rtvals);
                    if (mfOpt.isEmpty())
                        return;
                    parent = addToOrMakeParent(mfOpt.get(), parent, ft);
                }
                case "editor" -> {
                    var editOpt = EditorStepFab.buildEditorStep(step, delimiter, rtvals);
                    if (editOpt.isEmpty())
                        return;
                    parent = addToOrMakeParent(editOpt.get(), parent, ft);
                }
                case "cmd" -> {
                    var cmdOpt = StepFab.buildCmdStep(step, delimiter, rtvals);
                    if (cmdOpt.isEmpty())
                        return;
                    parent = addToOrMakeParent(cmdOpt.get(), parent, ft);
                }
                case "store" -> {
                    parent = addStoreStep(parent, step, ft);
                    if (parent == null)
                        return;
                }
                case "return" -> {
                    parent = addToOrMakeParent(new ReturnStep(), parent, ft);
                }
                default -> {
                }
            }
            ;
        }
    }

    private static AbstractStep addToOrMakeParent(AbstractStep step, AbstractStep parent, FabTools ft) {
        ft.setIfFailure(step);
        if (parent != null) {
            parent.setNext(step);
        } else {
            ft.addStep(step);
        }
        return step;
    }

    private static AbstractStep addStoreStep(AbstractStep parent, XMLdigger step, FabTools ft) {

        var store = ValStoreFab.buildValStore(step, ft.id, ft.rtvals);
        if (store.isInvalid())
            return null;
        var storeStep = new StoreStep(store);
        return addToOrMakeParent(storeStep, parent, ft);
    }

    private static class FabTools {
        RealtimeValues rtvals;
        String delimiter;
        String id;
        FilterStep lastIf = null;      // Temp holder for the last occurance of an 'if' to set the fail branch
        ArrayList<AbstractStep> steps = new ArrayList<>();

        public FabTools(RealtimeValues rtvals, String delimiter) {
            this.rtvals = rtvals;
            this.delimiter = delimiter;
        }

        public void setIfFailure(AbstractStep step) {
            if (lastIf != null)
                lastIf.setFailure(step);
            lastIf = null;
        }

        public void overwriteLastIf(FilterStep step) {
            lastIf = step;
        }

        public void addStep(AbstractStep step) {
            steps.add(step);
        }
    }
}
