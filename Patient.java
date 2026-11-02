public class Patient implements Runnable {
    private final String name;
    private final WaitingRoom waitingRoom;
    private boolean treated = false;

    public Patient(String name, WaitingRoom waitingRoom) {
        this.name = name;
        this.waitingRoom = waitingRoom;
    }

    public String getName() {
        return name;
    }

    // يتم استدعاؤها بواسطة الطبيب عند انتهاء علاج هذا المريض
    public synchronized void finishTreatment() {
        this.treated = true;
        notifyAll();
    }

    // ينتظر المريض هنا حتى ينتهي الطبيب من علاجه
    public synchronized void waitUntilTreated() throws InterruptedException {
        while (!treated) {
            wait();
        }
    }

    @Override
    public void run() {
        try {
            // 1. وصول المريض إلى المستشفى
            System.out.println(name + " arrived.");
            
            // 2. محاولة دخول صالة الانتظار
            waitingRoom.addPatient(this);
            System.out.println(name + " entered the waiting room.");

            // 3. انتظار انتهاء العلاج ومغادرة المستشفى
            waitUntilTreated();
            System.out.println(name + " left the hospital.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
