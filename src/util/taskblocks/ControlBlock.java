package util.taskblocks;

public class ControlBlock extends AbstractBlock{

    TaskBlock target;
    enum ACTIONS {START,STOP}

    ACTIONS TODO;

    public ControlBlock( TaskBlock target, String action ){
        this.target=target;
        if( target==null)
            valid=false;
        switch(action){
            case "stop":  TODO = ACTIONS.STOP; break;
            case "start": TODO = ACTIONS.START; break;
            default: valid=false;
        }
    }
    public static ControlBlock prepBlock( TaskBlock target, String action){
        return new ControlBlock(target,action);
    }
    @Override
    public boolean build() {
        return true;
    }

    @Override
    public boolean start(TaskBlock starter) {
        switch(TODO){
            case STOP : target.stop(); break;
            case START : target.start(this); break;
            default:
                nextFailed(this);
                return false;
        }
        nextOk();
        return true;
    }
    public String toString(){
        return switch (TODO) {
            case STOP -> "CB: Stop the block if running: " + target.toString();
            case START -> "CB: Start the block: " + target.toString();
        };
    }
}
