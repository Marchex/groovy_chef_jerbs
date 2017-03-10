def version = '1.0'

def configure_environment() {
    env.PATH="/opt/chefdk/bin:/var/lib/jenkins/.chefdk/gem/ruby/2.3.0/bin:/opt/chefdk/embedded/bin:${env.PATH}:/opt/chefdk/gitbin"
    env.GEM_ROOT="/opt/chefdk/embedded/lib/ruby/gems/2.3.0"
    env.GEM_HOME=".vendor/ruby/2.3.0"
    env.GEM_PATH=".vendor/ruby/2.3.0:/opt/chefdk/embedded/lib/ruby/gems/2.3.0"
    env.KITCHEN_YAML=".kitchen.ec2.yml"
    env.KITCHEN_EC2_SSH_KEY_PATH="/var/lib/jenkins/.ssh/tools-team.pem"
    env.KITCHEN_INSTANCE_NAME="test-kitchen-${env.JOB_NAME}"
}

def checkout_scm() {
    sh '/var/lib/jenkins/bin/rm_lockfiles'
    checkout scm
    bumped_version()
    sh '''
        gem list --local
        gem install foodcritic
        gem install bundler
        bundle install --path .vendor/
    '''
}

def bumped_version() {
    if (env.BRANCH_NAME == 'master') { return }
    echo 'Checking that version has been bumped'

    // bumped_version is in the hostclass_jenkins cookbook
    sh '/var/lib/jenkins/bin/bumped_version'
}

def lint() {
    sh 'rake lint'
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
    if (runbit == false) { return }
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm']) {
        // all kitchen commands run in parallel except verify, because
        // inspec hates parallel verifies.  it's a bug.  but this is
        // better anyway, because it groups tests together at the end,
        // and it only destroys *any* of the VMs if *all* of them succeed,
        // which can make debugging easier.

        // worse, we have a user-data script that runs in EC2 on create, and it
        // doesn't block between create/converge, and it runs apt, which
        // breaks converge (which also runs apt).  so, we create, then sleep,
        // then converge.

        // if the initial destroy fails, we want to ignore it and continue,
        // and we remove previous ymls in case they conflict
        
        // adding -c 8 because of AWS's API rate limit, which is subtle
        // and quick to anger
        sh '''
            bundle exec kitchen destroy -c 8 || true
            rm -rf ./.kitchen/*.yml
            bundle exec kitchen create -c 8
            sleep 30
            bundle exec kitchen converge -c 8
            bundle exec kitchen verify
            bundle exec kitchen destroy -c 8
        '''
    }
}

def publish(boolean runbit) {
    if (runbit == false) { return }
    // publish the cookbook to supermarket and chef, but only if this is
    // the merge to master
    if (env.BRANCH_NAME != 'master') { return }
    sh '/var/lib/jenkins/bin/publish_cookbook'
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
        stage ('Publish') { publish(run_banjo) }
        stage ('Cleanup') { cleanup() }
    }

    catch (err) {
        currentBuild.result = 'FAILURE'
        mail body: "${env.JOB_NAME} (${env.BUILD_NUMBER}) cookbook build error " +
                   "is here: ${env.BUILD_URL}\nStarted by ${env.BUILD_CAUSE}" ,
             from: 'tools-team@marchex.com',
             replyTo: 'tools-team@marchex.com',
             subject: "Jerbkins ${env.JOB_NAME} (${env.BUILD_NUMBER}) build failed",
             to: "${env.CHANGE_AUTHOR}@marchex.com"
        throw err
    }

    // null == pending for GitHub status
    // https://github.com/jenkinsci/github-branch-source-plugin/blob/1dc7ac4306e14dc9af791cb94ff8da96c98dbc07/src/main/java/org/jenkinsci/plugins/github_branch_source/GitHubBuildStatusNotification.java#L104
    if (currentBuild.result == null) {
        currentBuild.result = 'SUCCESS'
    }
}

// When all_the_jerbs is called with no args, run with kitchen and delivery by default
def all_the_jerbs() {
  all_the_jerbs(run_kitchen: true, run_delivery: true)
}

return this;
