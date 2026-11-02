public class TreatmentRooms {

    private Semaphore availableRooms;

    public TreatmentRooms() {
        availableRooms = new Semaphore(2);
    }

    public boolean acquireRoom() throws InterruptedException {
        return availableRooms.tryP(3000);
    }

    public void releaseRoom() {
        availableRooms.V();
    }
}
