def version = '1.0'

def configure_environment() {
    env.PATH="/opt/chefdk/bin:/var/lib/jenkins/.chefdk/gem/ruby/2.3.0/bin:/opt/chefdk/embedded/bin:${env.PATH}:/opt/chefdk/gitbin"
    env.GEM_ROOT="/opt/chefdk/embedded/lib/ruby/gems/2.3.0"
    env.GEM_HOME="./vendor/"
    env.GEM_PATH="./vendor/:/opt/chefdk/embedded/lib/ruby/gems/2.3.0"
    env.KITCHEN_YAML=".kitchen.ec2.yml"
    env.KITCHEN_EC2_SSH_KEY_PATH="/var/lib/jenkins/.ssh/tools-team.pem"
    env.KITCHEN_INSTANCE_NAME="test-kitchen-${env.JOB_NAME}"
}

def checkout_scm() {
    checkout scm
    sh '''
        gem list --local
        gem install foodcritic
        gem install bundler
        bundle install --path .vendor/             
    '''
}

def lint() {
    sh """
        rake lint
    """
    echo "RESULT: ${currentBuild.result}"
}

def chefspec() {
    sh """
        echo '--format RspecJunitFormatter' > .rspec
        echo '--out result.xml' >> .rspec
        rake chefspec
    """

    step([$class: 'JUnitResultArchiver', testResults: 'result.xml'])
}

def kitchen(boolean runbit) {
    if (runbit == false) { 
        echo "INSIDE THE RUNBIT"
        return 
        
    }
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm']) {
        sh """
            bundle exec kitchen destroy
            rm -rf ./.kitchen/*.yml
            bundle exec kitchen test -c 8
        """
    }
}

def deliverance(boolean runbit) {
    if (runbit == false) { return }
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm']) 
    {
        // do_delivery is in the hostclass_jenkins cookbook
        sh """
            /var/lib/jenkins/bin/do_delivery
        """
    } 
}

def cleanup() {
   echo 'Cleanup'
    // TODO: make this a slack channel notification
    //   mail body: "${env.BUILD_URL} build successful.\n" +
    //               "Started by ${env.BUILD_CAUSE}",
    //         from: 'tools-team@marchex.com',
    //         replyTo: 'tools-team@marchex.com',
    //         subject: "hostclass_publicftp ${env.JOB_NAME} (${env.BUILD_NUMBER}) build successful",
    //         to: 'jcarter@marchex.com'
}

def all_the_jerbs(Map args) {
    run_kitchen = (args.run_kitchen != null) ? args.run_kitchen : true
    run_banjo = (args.run_delivery != null) ? args.run_delivery : true
    configure_environment()
    try {
        stage ('Checkout') { checkout_scm() }
        stage ('Lint') { lint() }
        stage ('ChefSpec') { chefspec() }
        stage ('TestKitchen') { kitchen(run_kitchen) }
        stage ('Delivery Review') { deliverance(run_banjo) }
        stage ('Cleanup') { cleanup() }
    }

    catch (err) {
        currentBuild.result = "FAILURE"
        mail body: "${env.JOB_NAME} (${env.BUILD_NUMBER}) cookbook build error " +
                   "is here: ${env.BUILD_URL}\nStarted by ${env.BUILD_CAUSE}" ,
             from: 'tools-team@marchex.com',
             replyTo: 'tools-team@marchex.com',
             subject: "Jerbkins ${env.JOB_NAME} (${env.BUILD_NUMBER}) build failed",
             to: 'jcarter@marchex.com'
        throw err
    }
}

return this;
