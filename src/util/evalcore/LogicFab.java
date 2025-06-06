package util.evalcore;

import org.tinylog.Logger;
import util.data.vals.NumericVal;
import util.data.vals.Rtvals;

import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

public class LogicFab {

    private static final Map<String, String> compareInverts = new LinkedHashMap<>();
    static {
        compareInverts.put("<=", "#LE#");
        compareInverts.put(">=", "#GE#");
        compareInverts.put("==", "#EQ#");
        compareInverts.put("<", "#L#");
        compareInverts.put(">", "#G#");
        compareInverts.put("!=", "#NE#");
        compareInverts.put("&&", "#AND#");
        compareInverts.put("||", "#OR#");
        compareInverts.put("#LE#",">");
        compareInverts.put("#GE#","<");
        compareInverts.put("#EQ#","!=");
        compareInverts.put("#L#",">=");
        compareInverts.put("#G#","<=");
        compareInverts.put("#NE#","==");
        compareInverts.put("#AND#", "||");
        compareInverts.put("#OR#", "&&");
    }
    private static final Map<String, String> logicReplacements = new LinkedHashMap<>();
    static {
        logicReplacements.put("and", "&&");
        logicReplacements.put("exor", "!|");
        logicReplacements.put("or", "||");
        logicReplacements.put("from", "between");
        logicReplacements.put("to", "till");  // Ensure to only replace the range "to"
        logicReplacements.put("till", "&&");
        logicReplacements.put("through", "&&");
        logicReplacements.put("not below", ">=");   // retain support for below
        logicReplacements.put("not above", "<=");   // retain support for above
        logicReplacements.put("at least", ">=");
        logicReplacements.put("below", "<");   // retain support for below
        logicReplacements.put("above", ">");   // retain support for above
        logicReplacements.put("equals", "=="); // retain support for equals
        logicReplacements.put("not", "!=");
        logicReplacements.put("++", "+=1");
        logicReplacements.put("--", "-=1");
        logicReplacements.put("diff", "~");// diff?
    }

    public static Optional<LogicEvaluator> parseComparison(String exp, Rtvals rtvals, List<NumericVal> oldRefs) {
        var ori = exp;

        exp=logicTextToSymbols(exp);
        if( exp.isEmpty() ){
            Logger.error("Something went wrong (fe. brackets) during normalizing of "+exp);
            return Optional.empty();
        }
        // Rewrite the i's to reflect position in refLookup
        var refLookup = new ArrayList<Integer>();
        var is = ParseTools.extractIreferences(exp);
        var highestI = -1;
        if( is.length>0) {
            highestI = is[is.length - 1];
            for (var i : is) {
                exp = exp.replace("i" + i, "r" + refLookup.size());
                refLookup.add(i);
            }
        }

        // Replace rtvals references to rx instead
        var refs = new ArrayList<>( Objects.requireNonNullElse(oldRefs, new ArrayList<>()) );
        exp = ParseTools.replaceRealtimeValues(exp, refs, rtvals, refLookup);
        if( exp.isEmpty() ) {
            Logger.error("Failed the step of extracting the rtvals from the expression");
            return Optional.empty();
        }
        var normalized = exp;

        // With references cleaned up, split it all in processable blocks
        // Maybe simplify is single operand? like a+1<0
        var blocks = splitInBlocks(exp);
        var join = new StringJoiner("\r\n");
        for( var block :blocks ){
            join.add( block[0] +" -> "+block[1]);
        }

        // Start filling the eval
        var logicEval = new LogicEvaluator(ori, normalized, join.toString(), blocks.size()); // Set the amount of expected ops
        logicEval.setHighestI(highestI); // Highest encountered I reference
        logicEval.setRefs(refs.toArray(NumericVal[]::new)); // All encountered vals and temps
        logicEval.setRefLookup(refLookup.toArray(Integer[]::new));  // Array to link the r* to the actual values

        // Determine here if math or logic?

        // Split the blocks in parts to easily convert to functions
        blocks.replaceAll(LogicFab::extractParts);

        for( int a=0;a<blocks.size();a++) {
            var parts = blocks.get(a);

            if (parts.length < 3) {
                Logger.error("Not enough parts in block " + a + " as part of " + ori + " -> Parts:" + String.join("/", parts));
                return Optional.empty();
            }

            var function = ParseTools.decodeDoublesOp(parts[0],parts[2],parts[1],0);
            var ss= Double.NaN;
            if( parts[3].equals("||"))
                ss=1.0;
            if( parts[3].equals("&&"))
                ss=0.0;
            logicEval.addOp(a,function,ss);
        }
        if( blocks.size()==1 ){
            Logger.info("Simple comparison -> " + ori);
        }
        return Optional.of(logicEval);
    }
    private static String[] extractParts( String[] blocks ){
        var list = ParseTools.extractParts(blocks[0]);
        if( list.isEmpty() ) {
            Logger.error("Failed to split blocks in parts: "+blocks[0]);
            return blocks;
        }
        if( blocks.length>=2)
            list.add(blocks[1]==null?"":blocks[1]);
        return list.toArray(String[]::new);
    }
    private static String expandFlagOrIssueState(String exp, String compare) {
        String name = compare.split(":")[1];
        var offset = compare.startsWith("!") ? 1 : 0;
        String type = compare.substring(offset, 1 + offset);
        return exp.replace(compare, "{" + type + ":" + name + "}==" + (compare.startsWith("!") ? "0" : "1"));
    }
    static String logicTextToSymbols(String operation) {

        for (Map.Entry<String, String> entry : logicReplacements.entrySet()){
            operation=operation.replace(entry.getKey(),entry.getValue());
        }
        ArrayList<String> items = new ArrayList<>(Arrays.asList(operation.split(" ")));
        // Alter between or not between
        var i = items.indexOf("between");
        if( i>-1 ) {
            if (i > 0 && items.get(i - 1).equals("!=")) { // Not between
                var is = items.get(i - 2);
                var d1 = items.get(i + 1);
                var d2 = items.get(i + 3);

                var op = new String[]{"(", is, "<", d1, "&&", is, ">", d2, ")"};
                IntStream.range(0, 6).forEach(x -> items.remove(i - 2));
                IntStream.range(0, op.length).forEach(x -> items.add(i - 2 + x, op[x]));
            } else {
                var is = items.get(i - 1);
                var d1 = items.get(i + 1);
                var d2 = items.get(i + 3);

                var op = new String[]{"(", is, ">=", d1, "&&", is, "<=", d2, ")"};
                IntStream.range(0, 5).forEach(x -> items.remove(i - 1));
                IntStream.range(0, op.length).forEach(x -> items.add(i - 1 + x, op[x]));
            }
        }
        var res = String.join("", items);
        res = ParseTools.checkBrackets(res,'(',')',false);
        return res;
    }

    private static ArrayList<String[]> splitInBlocks(String exp ){

        ArrayList<String[]> results = new ArrayList<>();
        while( exp.contains("||") || exp.contains("&&") ) {
            var begin = firstLogicBlock(exp);
            var end = lastLogicBlock(exp);

            if(begin[0].equals(end[0])) {
                addItem(results,begin);
                if(exp.equals(begin[0]))
                    return results;
                exp = exp.substring(0,begin[0].length()+begin[1].length());
                exp = removeEnclosingBrackets(exp);
                continue;
            }

            boolean pickBegin = true;
            if( begin[1].equals(end[1])) {
                if (begin[0].contains("||") || begin[0].contains("&&")) { // begin is longer...
                    pickBegin=false;
                }
            }else if( !begin[1].equals("||")){
                pickBegin=false;
            }
            if( pickBegin ){
                exp = exp.substring(begin[0].length()+begin[1].length());
                addItem(results,begin);
            }else{
                exp = exp.substring(0,exp.length() - end[0].length() - end[1].length());
                addItem(results,end);
            }
            exp=removeEnclosingBrackets(exp);
        }
        results.add(new String[]{exp,""});
        return results;
    }
    private static void addItem( ArrayList<String[]> result, String[] item){
        if( item[0].contains("(") ){
            item[0]=removeBracketsAndNegate(item[0]);
            if( item[0].contains("&&")||item[1].contains("||")) {
                result.addAll(splitInBlocks(item[0]));
                return;
            }
        }
        result.add(item);
    }
    private static String removeEnclosingBrackets(String exp ){
        if( ParseTools.hasTotalEnclosingBrackets(exp,'(',')') )
            exp = exp.substring(1, exp.length() - 1);
        return exp;
    }
    private static String[] firstLogicBlock( String exp ){

        var start = exp.indexOf("(");
        var close = exp.indexOf(")");
        var res = new String[]{"",""};

        if( start < 2 && start!=-1 ){
            res[0]=exp.substring(0,close+1);
            if( close+1 != exp.length())
                res[1] = exp.substring(close + 1, close + 3);
            return res;
        }
        var firstAnd = exp.indexOf("&&");
        var firstOr = exp.indexOf("||");

        if( firstAnd==-1 && firstOr==-1)
            return new String[]{exp,""};

        if( firstAnd==-1)
            return new String[]{exp.substring(0,firstOr),"||"};
        if( firstOr==-1)
            return new String[]{exp.substring(0,firstAnd),"&&"};

        var end = Math.min(firstOr,firstAnd);

        res[0]=exp.substring(0,end);
        if( end != exp.length()-1 )
            res[1]=exp.substring(end,end+2);
        return res;

    }
    private static String[] lastLogicBlock( String exp ){
        var res = new String[2];

        if( exp.charAt(exp.length()-1)==')' ){
            var open = exp.lastIndexOf("(");
            var offset = 0;
            if( exp.contains("!"))
                offset = exp.charAt(open-1)=='!'?1:0;
            res[0]=exp.substring(open-offset);

            if(open-offset!=0)
                res[1]=exp.substring(open-2-offset,open);
            return res;
        }

        var lastAnd = exp.lastIndexOf("&&");
        var lastOr = exp.lastIndexOf("||");

        if( lastAnd==-1 && lastOr==-1)
            return new String[]{exp,""};
        if( lastAnd==-1)
            return new String[]{exp.substring(lastOr+2),"||"};
        if( lastOr ==-1)
            return new String[]{exp.substring(lastAnd+2),"&&"};

        var end = Math.max(lastOr,lastAnd);

        res[0]=exp.substring(end+2);
        if( end!=exp.length()-1)
            res[1]=exp.substring(end,end+2);
        return res;
    }
    private static String removeBracketsAndNegate(String exp){
        boolean negate = exp.startsWith("!");
        if( negate )
            exp=invertComparison(exp);
        exp = exp.substring(1+(negate?1:0), exp.length() - 1);
        return exp;
    }
    private static String invertComparison(String exp){
        for( var entry : compareInverts.entrySet() ){
            exp=exp.replace(entry.getKey(),entry.getValue());
        }
        return exp;
    }
    /* **************************** F U N C T I O N S ************************************************** */
    /**
     * Convert the comparison sign to a compare function that accepts a double array
     * @param comp The comparison fe. < or >= etc
     * @return The function or null if an invalid comp was given
     */
    private static Function<double[],Boolean> getCompareFunction(String comp ){
        Function<double[],Boolean> proc=null;
        switch( comp ){
            case "<":  proc = x -> Double.compare(x[0],x[1])<0;  break;
            case "<=": proc = x -> Double.compare(x[0],x[1])<=0; break;
            case ">":  proc = x -> Double.compare(x[0],x[1])>0;  break;
            case ">=": proc = x -> Double.compare(x[0],x[1])>=0; break;
            case "==": proc = x -> Double.compare(x[0],x[1])==0; break;
            case "!=": proc = x -> Double.compare(x[0],x[1])!=0; break;
            default:
                Logger.error( "Tried to convert an unknown compare to a function: "+comp);
        }
        return proc;
    }
    /**
     * Convert the given fixed value and comparison to a function that requires another double and return if correct
     * @param fixed The fixed part of the comparison
     * @param comp The type of comparison (options: <,>,<=,>=,!=,==)
     * @return The generated function
     */
    private static Function<Double,Boolean> getSingleCompareFunction( double fixed,String comp ) {
        Function<Double, Boolean> proc = null;
        switch (comp) {
            case "<":
                proc = x -> Double.compare(x, fixed) < 0;
                break;
            case "<=":
                proc = x -> Double.compare(x, fixed) <= 0;
                break;
            case ">":
                proc = x -> Double.compare(x, fixed) > 0;
                break;
            case ">=":
                proc = x -> Double.compare(x, fixed) >= 0;
                break;
            case "==":
                proc = x -> Double.compare(x, fixed) == 0;
                break;
            case "!=":
                proc = x -> Double.compare(x, fixed) != 0;
                break;
            default:
                Logger.error("Tried to convert an unknown compare to a function: " + comp);
        }
        return proc;
    }
    /**
     * Combine two function that return a double into a single one the compares the result of the two
     * @param comp The kind of comparison fe. < or >= etc
     * @param f1 The left function
     * @param f2 The right function
     * @return The resulting function
     */
    private static Function<Double[],Boolean> getCompareFunction( String comp, Function<Double[],Double> f1, Function<Double[],Double> f2 ){
        Function<Double[],Boolean> proc=null;
        switch (comp) {
            case "<" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) < 0;
            case "<=" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) <= 0;
            case ">" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) > 0;
            case ">=" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) >= 0;
            case "==" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) == 0;
            case "!=" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) != 0;
            default -> Logger.error("Tried to convert an unknown compare to a function: " + comp);
        }
        return proc;
    }
}
