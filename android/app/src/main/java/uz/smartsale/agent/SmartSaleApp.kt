package uz.smartsale.agent

import android.app.Application
import uz.smartsale.agent.data.Repository
import uz.smartsale.agent.data.SyncWorker

class SmartSaleApp : Application() {

    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        SyncWorker.schedule(this)
    }
}
