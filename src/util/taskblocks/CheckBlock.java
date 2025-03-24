package util.taskblocks;

import org.tinylog.Logger;
import util.data.RealVal;
import util.data.RealtimeValues;
import util.data.ValTools;
import util.math.MathUtils;
import util.tools.Tools;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class CheckBlock extends AbstractBlock{

    RealtimeValues rtvals;
    ArrayList<Function<Double[],Double>> steps = new ArrayList<>();
    int resultIndex;
    boolean negate = false;

    public CheckBlock(RealtimeValues rtvals,String set){
        this.rtvals=rtvals;
        this.ori=set;
    }
    public static CheckBlock prepBlock(RealtimeValues rtvals, String set){
        if( set.isEmpty() )
            return null;
        var block = new CheckBlock(rtvals,set);
        block.build();
        return block;
    }
    public void setNegate(boolean neg){
        negate=neg;
    }

    public void nextOk(){

    }
    public boolean alterSharedMem( int index, double val ){
        if( Double.isNaN(val))
            return false;
        while( sharedMem.size()<=index )
            sharedMem.add(RealVal.newVal("","i"+sharedMem.size()).value(0));
        sharedMem.get(index).updateValue(val);
        return true;
    }
    @Override
    public boolean start(TaskBlock starter) {
        if( !valid) {
            Logger.error("Checkblock failed because invalid: "+ori);
            return false;
        }
        Double[] work= new Double[steps.size()+sharedMem.size()];
        for (int a = 0; a < sharedMem.size();a++ ){
            work[steps.size()+a]=sharedMem.get(a).asDoubleValue();
        }
        for( int a=0;a<steps.size();a++)
            work[a]=steps.get(a).apply(work);
        var pass = Double.compare(work[resultIndex],0.0)>0;
        pass = negate != pass;
        if( pass ) {
            doNext();
            parentBlock.ifPresent( TaskBlock::nextOk );
        }else if( parentBlock.isPresent() ){
            Logger.debug( "Check failed : "+ori );
            if( parentBlock.get() instanceof TriggerBlock ) // needs to know because of while/waitfor
                parentBlock.get().nextFailed(this);
        }
        return pass;
    }

    public boolean build(){

        if( ori.isEmpty()) {
            Logger.error("No expression to process.");
            valid=false;
            return false;
        }
        // Fix the flag/issue negation and diff?
        var exp = fixFlagAndIssueNegation();

        // Replace the words used for the comparisons with the math equivalent
        // e.g. below becomes < and so on
        exp = Tools.mapExpressionToSymbols(exp); // rewrite to math symbols

        // Figure out the realtime stuff and populate the sharedMemory with it
        exp = populateSharedMemory(exp);

        if( exp.isEmpty() ){
            Logger.error( "Couldn't process "+ori+", vals missing");
            valid=false;
            return false;
        }

        // Figure out the brackets?
        var subOp = splitInSubExpressions(exp);
        if( subOp.isEmpty()) {
            valid=false;
            return false;
        }
        var subFormulas = subOp.get();
        if( subFormulas.isEmpty() )
            return false;

        resultIndex=subFormulas.size()-1;

        // Convert the sub formulas to functions
        subFormulas.forEach( sub -> {
            sub=sub.startsWith("!")?sub.substring(1)+"==0":sub;
            var parts = MathUtils.extractParts(sub);
            try {
                steps.add(MathUtils.decodeDoublesOp(parts.get(0), parts.size() == 3 ? parts.get(2) : "", parts.get(1), subFormulas.size()));
            }catch( IndexOutOfBoundsException e){
                Logger.error("CheckBox error during steps adding: "+ e.getMessage());
            }
        });

        return true;
    }
    private String fixFlagAndIssueNegation(){
        // Find comparisons optionally surrounded with curly brackets
        // Legacy or alternative notation for a flag is flag:value without a comparison (e.g. ==1)
        Pattern words = Pattern.compile("\\{?[!a-zA-Z:_]+[0-9]*[a-zA-Z]+\\d*}?");

        var foundComparisons = words.matcher(ori).results().map(MatchResult::group).toList();
        String expression = ori;
        for( var compare : foundComparisons ) {
            // Fixes flag:, !flag and issue:/!issue:
            if( compare.contains("flag:") || compare.startsWith("issue:")){
                expression=expandFlagOrIssueState( expression ,compare );
            }else if( compare.toLowerCase().startsWith("d:") || compare.toLowerCase().startsWith("f:")){ // not surrounded with brackets
                expression = expression.replace(compare,"{"+compare+"}"); // So add them
            }
        }
        return expression;
    }
    private String expandFlagOrIssueState( String exp, String compare ){
        String name = compare.split(":")[1];
        String type = compare.substring(0,1);
        return exp.replace(compare,"{"+type+":"+name+"}=="+(compare.startsWith("!")?"0":"1"));
    }
    private String populateSharedMemory(String exp){
        if( rtvals != null ) {
            exp = ValTools.buildNumericalMem(rtvals,exp, sharedMem, 0);
            if( exp.matches("i0"))
                exp += "==1";
        }else{
            Logger.warn("No rtvals, skipping numerical mem");
        }
        return exp;
    }
    private Optional<ArrayList<String>> splitInSubExpressions(String exp ){

        if( !checkBrackets(exp)){
            valid=false;
            return Optional.empty();
        }

        if( exp.charAt(0)!='(') // Make sure it has surrounding brackets
            exp= "("+exp+")";

        var subFormulas = new ArrayList<String>(); // List to contain all the sub-formulas
        // Fill the list by going through the brackets from left to right (inner)
        while( exp.contains("(") ){ // Look for an opening bracket
            int close = exp.indexOf(")"); // Find the first closing bracket
            // Find the index of the matching opening bracket by looking for the last occurrence before the closing one
            int open = exp.substring(0,close-1).lastIndexOf("(");

            String part = exp.substring(open+1,close); // get the part between the brackets
            exp = exp.replace( exp.substring(open,close+1),"$$"); // Replace the sub with the placeholder

            // Split part on && and ||
            var and_ors = part.split("[&|!]{2}",0);
            for( var and_or : and_ors) {
                var comps = and_or.split("[><=!]=?"); // Split on the compare ops
                for (var c : comps) { // Go through the elements
                    if( !(c.matches("[io]+\\d+")||c.matches("\\d*[.]?\\d*"))&&!c.isEmpty() ){
                        // If NOT ix,ox or a number
                        int index = subFormulas.indexOf(c);
                        if (index == -1) {
                            subFormulas.add(c);    // split that part in the sub-formulas
                            index = subFormulas.size() - 1;
                        }
                        and_or = and_or.replace(c, "o" + index);
                        part = part.replace(c, "o" + index);
                    }
                }
                if(!(and_or.matches("[io]+\\d+")||and_or.matches("\\d*"))) {
                    subFormulas.add(and_or);
                    part = part.replace(and_or, "o" + (subFormulas.size() - 1));
                }
            }
            part=part.replace("&&","*")
                        .replace("||","+");

            if(part.contains("!|"))
                part="("+part.replace("!|","+")+")%2";

            exp=exp.replace("$$",part);
        }
        if( exp.length()!=2)
            subFormulas.add(exp);
        return Optional.of(subFormulas);
    }
    private boolean checkBrackets( String exp ){
        int openCount = 0;
        for (char ch : exp.toCharArray()) {
            if (ch == '(') {
                openCount++;
            } else if (ch == ')') {
                openCount--;
                if (openCount < 0) {
                    Logger.error("Order of open and closing brackets incorrect in "+exp);
                    return false;
                }
            }
        }
        if( openCount!=0 ){
            Logger.error("Mismatched count of open and closing brackets in "+exp);
            return false;
        }
        return true;
    }
    public String toString(){
        return "Check if "+ori;
    }

}
