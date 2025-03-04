package util.taskblocks;

import util.data.NumberVal;
import util.data.NumericVal;

import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

public interface TaskBlock {

    /* Shared Numerical Mem */
    void setSharedMem( ArrayList<NumberVal<? extends Number>> mem);
    boolean hasSharedMem();
    ArrayList<NumberVal<? extends Number>> getSharedMem();

    boolean addNext(TaskBlock block);
    Optional<TaskBlock> getParent();
    TaskBlock link( TaskBlock parent);
    boolean addData(String data);
    boolean build();
    boolean start(TaskBlock starter);
    boolean stop();

    void nextOk();
    void nextFailed(TaskBlock failed);
    void getBlockInfo(StringJoiner join,String offset);

    Optional<TaskBlock> getSourceBlock();
}
