package util.taskblocks;

import org.tinylog.Logger;
import util.data.NumericVal;
import util.task.Task;

import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

public abstract class AbstractBlock implements TaskBlock{
    ArrayList<NumericVal> sharedMem = new ArrayList<>();
    ArrayList<TaskBlock> next = new ArrayList<>();
    String ori;
    Optional<TaskBlock> parentBlock=Optional.empty();
    boolean stopFuture=false;

    boolean srcBlock=false;
    boolean valid=true;

    /* Shared mem */
    public void setSharedMem(ArrayList<NumericVal> mem) {
        sharedMem=mem;
    }
    public boolean hasSharedMem(){
        return !sharedMem.isEmpty();
    }
    public ArrayList<NumericVal> getSharedMem(){
        return sharedMem;
    }

    /* Hierarchy */
    public TaskBlock link( TaskBlock parent ){
        parentBlock = Optional.ofNullable(parent);
        parentBlock.ifPresentOrElse( tb->tb.addNext(this), ()->srcBlock=true);

        if( srcBlock ){
            sharedMem = new ArrayList<>();
        }else if( parent != null ){
            sharedMem = parent.getSharedMem();
        }
        return this;
    }
    public Optional<TaskBlock> getParent(){
        return parentBlock;
    }
    public Optional<TaskBlock> getSourceBlock(){
        if( srcBlock )
            return Optional.of(this);
        return parentBlock.flatMap(TaskBlock::getSourceBlock);
    }
    public TaskBlock getLastNext(){
        if(next.isEmpty())
            return null;
        return next.get(next.size()-1);
    }
    public boolean addNext(TaskBlock block) {
        next.add(block);
        return true;
    }
    public void doNext() {
        if( stopFuture ){
            stopFuture=false;
            return;
        }
        next.forEach( tb-> tb.start(this));
        if( next.isEmpty()) // If this is the last block in the branch, notify parent
            nextOk();
    }
    public boolean addData(String data){
        return true;
    }

    @Override
    public void getBlockInfo(StringJoiner join,String offset) {
        var list = ("-> "+this).split("\r\n");
        for( var s:list)
            join.add(offset + s);
        for( var b : next ){
            b.getBlockInfo(join,offset+"  ");
        }
    }
    public boolean matchesOri( String com ){
        return ori.equalsIgnoreCase(com);
    }
    public boolean start(TaskBlock starter){
        doNext();
        return true;
    }
    public boolean stop(){
        next.forEach(TaskBlock::stop);
        return true;
    }
    public boolean isValid(){
        return valid;
    }
    public void nextOk(){
        parentBlock.ifPresent(TaskBlock::nextOk);
    }
    public void nextFailed(TaskBlock failed){
        parentBlock.ifPresent(taskBlock -> taskBlock.nextFailed(failed));
    }
}

